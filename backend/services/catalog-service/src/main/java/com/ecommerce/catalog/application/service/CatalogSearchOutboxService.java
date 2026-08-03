package com.ecommerce.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.SearchModels.SearchOutboxView;
import com.ecommerce.catalog.application.model.SearchModels.SearchRecoveryView;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSpuEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchOutboxEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRecoveryAuditEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductSpuMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchOutboxMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRecoveryAuditMapper;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class CatalogSearchOutboxService {

    private final ProductSpuMapper productMapper;
    private final SearchOutboxMapper outboxMapper;
    private final SearchRecoveryAuditMapper recoveryMapper;
    private final CatalogSearchProperties properties;

    public CatalogSearchOutboxService(
            ProductSpuMapper productMapper,
            SearchOutboxMapper outboxMapper,
            SearchRecoveryAuditMapper recoveryMapper,
            CatalogSearchProperties properties) {
        this.productMapper = productMapper;
        this.outboxMapper = outboxMapper;
        this.recoveryMapper = recoveryMapper;
        this.properties = properties;
    }

    public void recordProductChanged(Long productId) {
        if (!properties.enabled()) {
            return;
        }
        if (productMapper.incrementSearchRevision(productId) != 1) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        ProductSpuEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        enqueue(productId, product.getSearchRevision());
    }

    @Transactional
    public void enqueueRepair(Long productId, long targetRevision) {
        if (!properties.enabled()) {
            throw new CatalogException(CatalogError.SEARCH_INDEX_UNAVAILABLE);
        }
        enqueue(productId, targetRevision);
    }

    @Transactional(readOnly = true)
    public List<SearchOutboxView> list(String status, int limit) {
        return outboxMapper.selectByStatus(status, limit).stream().map(this::view).toList();
    }

    @Transactional
    public SearchRecoveryView recover(
            String outboxId,
            long operatorId,
            String commandId,
            String reason) {
        String requestHash = sha256(outboxId + "|" + operatorId + "|" + reason.trim());
        SearchRecoveryAuditEntity existing = recoveryMapper.selectByCommandId(commandId);
        if (existing != null) {
            requireSameHash(existing.getRequestHash(), requestHash);
            return recoveryView(existing);
        }

        SearchOutboxEntity event = outboxMapper.selectByIdForUpdate(outboxId);
        if (event == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        existing = recoveryMapper.selectByCommandIdForUpdate(commandId);
        if (existing != null) {
            requireSameHash(existing.getRequestHash(), requestHash);
            return recoveryView(existing);
        }
        if (!"NEEDS_ATTENTION".equals(event.getStatus())) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }

        Instant now = now();
        SearchRecoveryAuditEntity audit = new SearchRecoveryAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCommandId(commandId);
        audit.setOutboxId(outboxId);
        audit.setOperatorId(operatorId);
        audit.setReason(reason.trim());
        audit.setRequestHash(requestHash);
        audit.setStatusBefore("NEEDS_ATTENTION");
        audit.setStatusAfter("PENDING");
        audit.setCreatedAt(now);
        int inserted = recoveryMapper.insertIdempotent(audit);
        SearchRecoveryAuditEntity persisted =
                recoveryMapper.selectByCommandIdForUpdate(commandId);
        if (persisted == null) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        requireSameHash(persisted.getRequestHash(), requestHash);
        if (inserted == 0) {
            return recoveryView(persisted);
        }
        if (outboxMapper.recover(outboxId, now) != 1) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        return recoveryView(persisted);
    }

    private void enqueue(Long productId, long targetRevision) {
        Instant now = now();
        SearchOutboxEntity event = new SearchOutboxEntity();
        event.setId(UUID.randomUUID().toString());
        event.setProductId(productId);
        event.setTargetRevision(targetRevision);
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private SearchOutboxView view(SearchOutboxEntity event) {
        return new SearchOutboxView(
                event.getId(),
                event.getProductId(),
                event.getTargetRevision(),
                event.getStatus(),
                event.getAttempts(),
                event.getNextAttemptAt(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }

    private SearchRecoveryView recoveryView(SearchRecoveryAuditEntity audit) {
        return new SearchRecoveryView(
                audit.getCommandId(),
                audit.getOutboxId(),
                audit.getStatusBefore(),
                audit.getStatusAfter(),
                audit.getCreatedAt());
    }

    private void requireSameHash(String existing, String supplied) {
        if (!MessageDigest.isEqual(
                existing.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Instant now() {
        return outboxMapper.currentTime();
    }
}
