package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.application.port.DomainEventPublisher;
import com.ecommerce.inventory.infrastructure.config.InventorySchedulingConfig;
import com.ecommerce.inventory.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.inventory.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "ecommerce.inventory.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private final OutboxEventMapper outboxMapper;
    private final DomainEventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxMetrics metrics;
    private final ExecutorService publicationExecutor;

    public OutboxPublisherJob(
            OutboxEventMapper outboxMapper,
            DomainEventPublisher publisher,
            OutboxProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.metrics = new OutboxMetrics(meterRegistry, "inventory-service",
                outboxMapper::countUnpublished, outboxMapper::selectOldestUnpublishedCreatedAt, clock);
        this.publicationExecutor = Executors.newFixedThreadPool(properties.parallelism(), runnable -> {
            Thread thread = new Thread(runnable, "inventory-outbox-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.inventory.outbox.fixed-delay:2000}",
            scheduler = InventorySchedulingConfig.OUTBOX_SCHEDULER)
    public void publishPendingEvents() {
        Instant now = outboxMapper.currentTime();
        metrics.staleClaimsRecovered(outboxMapper.resetStaleClaims(now, now));
        Map<String, List<OutboxEventEntity>> eventsByAggregate = new LinkedHashMap<>();
        for (OutboxEventEntity event : outboxMapper.selectPublishable(now, properties.batchSize())) {
            if (outboxMapper.claim(
                    event.getId(),
                    properties.publisherId(),
                    event.getAttempts(),
                    now,
                    now.plus(properties.leaseDuration()),
                    now) != 1) {
                metrics.claimContended();
                continue;
            }
            String aggregateKey = event.getAggregateType() + ":" + event.getAggregateId();
            eventsByAggregate.computeIfAbsent(aggregateKey, ignored -> new ArrayList<>()).add(event);
        }
        CompletableFuture<?>[] publications = eventsByAggregate.values().stream()
                .map(events -> CompletableFuture.runAsync(
                        () -> events.forEach(this::publishClaimed), publicationExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(publications).join();
    }

    private void publishClaimed(OutboxEventEntity event) {
        Timer.Sample sample = metrics.startPublication();
        try {
            publisher.publish(event.getEventId(), event.getEventType(), event.getPayload());
            Instant completedAt = outboxMapper.currentTime();
            if (outboxMapper.markPublished(
                    event.getId(), properties.publisherId(), completedAt) == 1) {
                metrics.publicationSucceeded(sample);
            } else {
                metrics.publicationStateConflict(sample);
                log.warn("Inventory event was sent but its publishing claim was lost: eventId={}, type={}",
                        event.getEventId(), event.getEventType());
            }
        } catch (Exception exception) {
            Instant failedAt = outboxMapper.currentTime();
            try {
                if (outboxMapper.markFailed(
                        event.getId(),
                        properties.publisherId(),
                        failedAt.plus(properties.retryDelay()),
                        conciseError(exception),
                        failedAt
                ) == 1) {
                    metrics.publicationFailed(sample);
                } else {
                    metrics.publicationStateConflict(sample);
                    log.warn("Inventory event failed after its publishing claim was lost: eventId={}, type={}",
                            event.getEventId(), event.getEventType());
                }
            } catch (RuntimeException persistenceException) {
                metrics.publicationFailed(sample);
                throw persistenceException;
            }
            log.warn("Inventory event publication failed and remains in outbox: eventId={}, type={}, error={}",
                    event.getEventId(), event.getEventType(), conciseError(exception));
        }
    }

    @PreDestroy
    public void close() {
        publicationExecutor.shutdown();
        try {
            if (!publicationExecutor.awaitTermination(
                    properties.shutdownAwait().toMillis(), TimeUnit.MILLISECONDS)) {
                publicationExecutor.shutdownNow();
                log.warn("Inventory Outbox publisher did not drain before the shutdown deadline");
            }
        } catch (InterruptedException exception) {
            publicationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Inventory Outbox publisher shutdown was interrupted");
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
