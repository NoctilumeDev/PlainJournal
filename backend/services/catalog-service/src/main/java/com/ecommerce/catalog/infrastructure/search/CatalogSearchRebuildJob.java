package com.ecommerce.catalog.infrastructure.search;

import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchProductDocument;
import com.ecommerce.catalog.application.service.CatalogSearchRebuildCutoverService;
import com.ecommerce.catalog.application.service.CatalogSearchReconciliationService;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRebuildMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ecommerce.catalog.search", name = "enabled", havingValue = "true")
public class CatalogSearchRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchRebuildJob.class);

    private final SearchRebuildMapper mapper;
    private final CatalogSearchProjectionReader reader;
    private final ProductSearchIndex index;
    private final CatalogSearchRebuildCutoverService cutoverService;
    private final CatalogSearchReconciliationService reconciliationService;
    private final CatalogSearchProperties properties;
    private final Counter succeeded;
    private final Counter failed;

    public CatalogSearchRebuildJob(
            SearchRebuildMapper mapper,
            CatalogSearchProjectionReader reader,
            ProductSearchIndex index,
            CatalogSearchRebuildCutoverService cutoverService,
            CatalogSearchReconciliationService reconciliationService,
            CatalogSearchProperties properties,
            MeterRegistry registry) {
        this.mapper = mapper;
        this.reader = reader;
        this.index = index;
        this.cutoverService = cutoverService;
        this.reconciliationService = reconciliationService;
        this.properties = properties;
        this.succeeded = Counter.builder("ecommerce.catalog.search.rebuilds")
                .tag("outcome", "success")
                .register(registry);
        this.failed = Counter.builder("ecommerce.catalog.search.rebuilds")
                .tag("outcome", "failure")
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.catalog.search.rebuild-fixed-delay:1000}",
            initialDelayString = "${ecommerce.catalog.search.rebuild-initial-delay:1000}",
            scheduler = "catalogSearchScheduler")
    public void rebuildPending() {
        Instant now = mapper.currentTime();
        mapper.resetStaleClaims(now);
        for (SearchRebuildEntity candidate : mapper.selectPending(1)) {
            rebuild(candidate);
        }
    }

    private void rebuild(SearchRebuildEntity candidate) {
        Instant claimedAt = mapper.currentTime();
        String workingIndex = properties.indexAlias()
                + "-v-" + candidate.getId()
                + "-run-" + UUID.randomUUID().toString().replace("-", "");
        if (mapper.claim(
                candidate.getId(),
                properties.workerId(),
                candidate.getAttempts(),
                workingIndex,
                claimedAt,
                claimedAt.plus(properties.leaseDuration())) != 1) {
            return;
        }
        long indexed = 0;
        try {
            index.createIndex(workingIndex);
            renewOrThrow(candidate.getId(), indexed);
            long afterId = 0;
            while (true) {
                List<SearchProductDocument> batch = reader.readActiveBatch(
                        afterId, properties.rebuildBatchSize());
                if (batch.isEmpty()) {
                    break;
                }
                index.bulkIndex(workingIndex, batch);
                indexed += batch.size();
                afterId = batch.get(batch.size() - 1).productId();
                renewOrThrow(candidate.getId(), indexed);
            }

            if (!cutoverService.cutover(
                    candidate.getId(),
                    properties.workerId(),
                    workingIndex,
                    indexed)) {
                throw new IllegalStateException("Search rebuild completion lease was lost");
            }
            succeeded.increment();
            try {
                reconciliationService.reconcile(true);
            } catch (RuntimeException reconciliationFailure) {
                log.warn("Catalog search rebuild succeeded but immediate catch-up reconciliation failed: rebuildId={}",
                        candidate.getId());
            }
            try {
                index.deleteOwnedIndicesExcept(workingIndex);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Catalog search rebuild succeeded but old index cleanup failed: rebuildId={}",
                        candidate.getId());
            }
        } catch (RuntimeException exception) {
            Instant failedAt = mapper.currentTime();
            mapper.markFailed(
                    candidate.getId(),
                    properties.workerId(),
                    properties.maxAttempts(),
                    conciseError(exception),
                    failedAt);
            failed.increment();
            log.warn("Catalog search rebuild failed and remains governed: rebuildId={}", candidate.getId());
        }
    }

    private void renewOrThrow(long rebuildId, long indexedCount) {
        Instant renewedAt = mapper.currentTime();
        if (mapper.renew(
                rebuildId,
                properties.workerId(),
                indexedCount,
                renewedAt,
                renewedAt.plus(properties.leaseDuration())) != 1) {
            throw new IllegalStateException("Search rebuild lease was lost");
        }
    }

    private String conciseError(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = describe(exception);
        if (root != exception) {
            message += " | rootCause=" + describe(root);
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private String describe(Throwable failure) {
        String detail = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}
