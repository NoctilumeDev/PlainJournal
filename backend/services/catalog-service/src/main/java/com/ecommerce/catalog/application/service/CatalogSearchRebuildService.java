package com.ecommerce.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.SearchModels.SearchRebuildRecoveryView;
import com.ecommerce.catalog.application.model.SearchModels.SearchRebuildView;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildRecoveryAuditEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRebuildMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRebuildRecoveryAuditMapper;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class CatalogSearchRebuildService {

    private final SearchRebuildMapper mapper;
    private final SearchRebuildRecoveryAuditMapper recoveryMapper;
    private final CatalogSearchProperties properties;

    public CatalogSearchRebuildService(
            SearchRebuildMapper mapper,
            SearchRebuildRecoveryAuditMapper recoveryMapper,
            CatalogSearchProperties properties) {
        this.mapper = mapper;
        this.recoveryMapper = recoveryMapper;
        this.properties = properties;
    }

    @Transactional
    public SearchRebuildView submit(long operatorId, String commandId, String reason) {
        requireEnabled();
        String normalizedReason = reason.trim();
        String requestHash = sha256(operatorId + "|" + normalizedReason);
        SearchRebuildEntity existing = mapper.selectByCommandId(commandId);
        if (existing != null) {
            requireSameHash(existing.getRequestHash(), requestHash);
            return view(existing);
        }

        Instant now = now();
        long id = IdWorker.getId();
        SearchRebuildEntity rebuild = new SearchRebuildEntity();
        rebuild.setId(id);
        rebuild.setCommandId(commandId);
        rebuild.setOperatorId(operatorId);
        rebuild.setReason(normalizedReason);
        rebuild.setRequestHash(requestHash);
        rebuild.setStatus("PENDING");
        rebuild.setTargetIndex(properties.indexAlias() + "-v-" + id);
        rebuild.setAttempts(0);
        rebuild.setIndexedCount(0L);
        rebuild.setCreatedAt(now);
        rebuild.setUpdatedAt(now);
        mapper.insertIdempotent(rebuild);
        SearchRebuildEntity persisted = mapper.selectByCommandIdForUpdate(commandId);
        if (persisted == null) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        requireSameHash(persisted.getRequestHash(), requestHash);
        return view(persisted);
    }

    @Transactional(readOnly = true)
    public SearchRebuildView get(long id) {
        SearchRebuildEntity rebuild = mapper.selectById(id);
        if (rebuild == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return view(rebuild);
    }

    @Transactional(readOnly = true)
    public List<SearchRebuildView> list(int limit) {
        return mapper.selectRecent(limit).stream().map(this::view).toList();
    }

    @Transactional
    public SearchRebuildRecoveryView recover(
            long rebuildId,
            long operatorId,
            String commandId,
            String reason) {
        requireEnabled();
        String normalizedReason = reason.trim();
        String requestHash = sha256(rebuildId + "|" + operatorId + "|" + normalizedReason);
        SearchRebuildRecoveryAuditEntity existing = recoveryMapper.selectByCommandId(commandId);
        if (existing != null) {
            requireSameHash(existing.getRequestHash(), requestHash);
            return recoveryView(existing);
        }
        SearchRebuildEntity rebuild = mapper.selectByIdForUpdate(rebuildId);
        if (rebuild == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        existing = recoveryMapper.selectByCommandIdForUpdate(commandId);
        if (existing != null) {
            requireSameHash(existing.getRequestHash(), requestHash);
            return recoveryView(existing);
        }
        if (!"NEEDS_ATTENTION".equals(rebuild.getStatus())) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        Instant now = now();
        SearchRebuildRecoveryAuditEntity audit = new SearchRebuildRecoveryAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCommandId(commandId);
        audit.setRebuildId(rebuildId);
        audit.setOperatorId(operatorId);
        audit.setReason(normalizedReason);
        audit.setRequestHash(requestHash);
        audit.setStatusBefore("NEEDS_ATTENTION");
        audit.setStatusAfter("PENDING");
        audit.setCreatedAt(now);
        int inserted = recoveryMapper.insertIdempotent(audit);
        SearchRebuildRecoveryAuditEntity persisted =
                recoveryMapper.selectByCommandIdForUpdate(commandId);
        if (persisted == null) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        requireSameHash(persisted.getRequestHash(), requestHash);
        if (inserted == 0) {
            return recoveryView(persisted);
        }
        if (mapper.recover(rebuildId, now) != 1) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
        return recoveryView(persisted);
    }

    public SearchRebuildView view(SearchRebuildEntity rebuild) {
        return new SearchRebuildView(
                rebuild.getId(),
                rebuild.getCommandId(),
                rebuild.getStatus(),
                rebuild.getTargetIndex(),
                rebuild.getAttempts(),
                rebuild.getIndexedCount(),
                rebuild.getLastError(),
                rebuild.getCreatedAt(),
                rebuild.getStartedAt(),
                rebuild.getCompletedAt());
    }

    private SearchRebuildRecoveryView recoveryView(SearchRebuildRecoveryAuditEntity audit) {
        return new SearchRebuildRecoveryView(
                audit.getCommandId(),
                audit.getRebuildId(),
                audit.getStatusBefore(),
                audit.getStatusAfter(),
                audit.getCreatedAt());
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new CatalogException(CatalogError.SEARCH_INDEX_UNAVAILABLE);
        }
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
        return mapper.currentTime();
    }
}
