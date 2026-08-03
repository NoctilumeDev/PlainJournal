package com.ecommerce.catalog.infrastructure.search;

import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchOutboxEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "ecommerce.catalog.search", name = "enabled", havingValue = "true")
public class CatalogSearchProjectionJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchProjectionJob.class);

    private final SearchOutboxMapper mapper;
    private final CatalogSearchProjectionReader reader;
    private final ProductSearchIndex index;
    private final CatalogSearchProperties properties;
    private final Counter succeeded;
    private final Counter failed;
    private final Counter claimContended;

    public CatalogSearchProjectionJob(
            SearchOutboxMapper mapper,
            CatalogSearchProjectionReader reader,
            ProductSearchIndex index,
            CatalogSearchProperties properties,
            MeterRegistry registry) {
        this.mapper = mapper;
        this.reader = reader;
        this.index = index;
        this.properties = properties;
        this.succeeded = Counter.builder("ecommerce.catalog.search.projection")
                .tag("outcome", "success")
                .register(registry);
        this.failed = Counter.builder("ecommerce.catalog.search.projection")
                .tag("outcome", "failure")
                .register(registry);
        this.claimContended = Counter.builder("ecommerce.catalog.search.claims")
                .tag("outcome", "contended")
                .register(registry);
        Gauge.builder("ecommerce.catalog.search.outbox.pending", mapper, SearchOutboxMapper::countUnpublished)
                .register(registry);
        Gauge.builder("ecommerce.catalog.search.outbox.needs.attention",
                        mapper, SearchOutboxMapper::countNeedsAttention)
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.catalog.search.projection-fixed-delay:1000}",
            initialDelayString = "${ecommerce.catalog.search.projection-initial-delay:1000}",
            scheduler = "catalogSearchScheduler")
    public void projectPending() {
        Instant now = mapper.currentTime();
        mapper.resetStaleClaims(now);
        for (SearchOutboxEntity event : mapper.selectDispatchable(now, properties.batchSize())) {
            project(event);
        }
    }

    private void project(SearchOutboxEntity event) {
        Instant claimedAt = mapper.currentTime();
        if (mapper.claim(
                event.getId(),
                properties.workerId(),
                event.getAttempts(),
                claimedAt,
                claimedAt.plus(properties.leaseDuration())) != 1) {
            claimContended.increment();
            return;
        }
        try {
            CatalogSearchProjectionReader.ProjectionState state = reader.readState(event.getProductId());
            if (state.document().isPresent()) {
                index.upsert(state.document().orElseThrow());
            } else {
                index.delete(event.getProductId(), Math.max(state.revision(), event.getTargetRevision()));
            }
            Instant completedAt = mapper.currentTime();
            if (mapper.markPublished(event.getId(), properties.workerId(), completedAt) == 1) {
                succeeded.increment();
            } else {
                log.warn("Catalog search projection completed after its lease was lost: outboxId={}",
                        event.getId());
            }
        } catch (RuntimeException exception) {
            Instant failedAt = mapper.currentTime();
            mapper.markFailed(
                    event.getId(),
                    properties.workerId(),
                    properties.maxAttempts(),
                    failedAt.plus(properties.retryDelay()),
                    conciseError(exception),
                    failedAt);
            failed.increment();
            log.warn("Catalog search projection failed and remains governed by the outbox: outboxId={}, productId={}",
                    event.getId(), event.getProductId());
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
