package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.infrastructure.config.TradeSchedulingConfig;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherJob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxEventMapper outboxMapper;
    private final OutboxClaimService claimService;
    private final DomainEventPublisher publisher;
    private final OutboxProperties properties;
    private final ProcessTerminationFaultInjector faultInjector;
    private final Clock clock;
    private final OutboxMetrics metrics;
    private final TradeShardRouter shardRouter;
    private final ExecutorService publicationExecutor;

    public OutboxPublisherJob(
            OutboxEventMapper outboxMapper,
            OutboxClaimService claimService,
            DomainEventPublisher publisher,
            OutboxProperties properties,
            ProcessTerminationFaultInjector faultInjector,
            Clock clock,
            MeterRegistry meterRegistry,
            TradeShardRouter shardRouter) {
        this.outboxMapper = outboxMapper;
        this.claimService = claimService;
        this.publisher = publisher;
        this.properties = properties;
        this.faultInjector = faultInjector;
        this.clock = clock;
        this.shardRouter = shardRouter;
        this.metrics = new OutboxMetrics(meterRegistry, "trade-service",
                outboxMapper::countUnpublished, outboxMapper::selectOldestUnpublishedCreatedAt, clock);
        this.publicationExecutor = Executors.newFixedThreadPool(properties.parallelism(), runnable -> {
            Thread thread = new Thread(runnable, "trade-outbox-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.trade.outbox.fixed-delay:2000}",
            scheduler = TradeSchedulingConfig.OUTBOX_SCHEDULER)
    public void publishPendingEvents() {
        OutboxClaimService.ClaimBatch claimBatch;
        try {
            claimBatch = claimService.claimBatch(
                    properties.publisherId(),
                    properties.leaseDuration(),
                    properties.batchSize());
        } catch (TransientDataAccessException exception) {
            metrics.claimContended();
            log.warn("Trade Outbox claim coordination hit a transient database conflict; "
                    + "the next scheduled run will retry: publisherId={}, error={}",
                    properties.publisherId(), conciseError(exception));
            return;
        }
        metrics.staleClaimsRecovered(claimBatch.staleClaimsRecovered());
        for (int index = 0; index < claimBatch.contendedClaims(); index++) {
            metrics.claimContended();
        }
        Map<String, List<OutboxClaimService.ClaimedEvent>> eventsByAggregate = new LinkedHashMap<>();
        for (OutboxClaimService.ClaimedEvent claimed : claimBatch.events()) {
            String aggregateKey = claimed.aggregateType() + ":" + claimed.aggregateId();
            eventsByAggregate.computeIfAbsent(
                    aggregateKey, ignored -> new java.util.ArrayList<>()).add(claimed);
        }
        CompletableFuture<?>[] publications = eventsByAggregate.values().stream()
                .map(events -> CompletableFuture.runAsync(
                        () -> events.forEach(this::publishClaimed), publicationExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(publications).join();
    }

    private void publishClaimed(OutboxClaimService.ClaimedEvent claimed) {
        Timer.Sample sample = metrics.startPublication();
        try {
            faultInjector.terminateIfArmed(
                    ProcessTerminationPoint.OUTBOX_BEFORE_PUBLISH, claimed.id());
            if (claimed.destinationTopic() == null) {
                publisher.publishAsync(
                        claimed.id(), claimed.eventType(), claimed.payload()).join();
            } else {
                publisher.publishAsync(
                        claimed.destinationTopic(),
                        claimed.id(),
                        claimed.eventType(),
                        claimed.payload()).join();
            }
            faultInjector.terminateIfArmed(
                    ProcessTerminationPoint.OUTBOX_AFTER_BROKER_ACK, claimed.id());
            int updated = shardRouter.executeOnShard(
                    claimed.shardIndex(),
                    () -> {
                        Instant completedAt = outboxMapper.currentTime();
                        return outboxMapper.markPublished(
                                claimed.id(), properties.publisherId(), completedAt);
                    });
            if (updated == 1) {
                metrics.publicationSucceeded(sample);
            } else {
                metrics.publicationStateConflict(sample);
                log.warn("Trade event was sent but its publishing claim was lost: eventId={}, type={}",
                        claimed.id(), claimed.eventType());
            }
        } catch (Exception exception) {
            int updated = shardRouter.executeOnShard(
                    claimed.shardIndex(),
                    () -> {
                        Instant failedAt = outboxMapper.currentTime();
                        return outboxMapper.markFailed(
                                claimed.id(),
                                properties.publisherId(),
                                failedAt.plus(properties.retryDelay()),
                                conciseError(exception),
                                failedAt);
                    });
            if (updated == 1) {
                metrics.publicationFailed(sample);
            } else {
                metrics.publicationStateConflict(sample);
                log.warn("Trade event failed after its publishing lease was lost: eventId={}, type={}",
                        claimed.id(), claimed.eventType());
            }
            log.warn("Trade event publication failed and remains in outbox: eventId={}, type={}, error={}",
                    claimed.id(), claimed.eventType(), conciseError(exception));
        }
    }

    @PreDestroy
    @Override
    public void close() {
        publicationExecutor.shutdown();
        try {
            if (!publicationExecutor.awaitTermination(
                    properties.shutdownAwait().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                publicationExecutor.shutdownNow();
                log.warn("Trade Outbox publisher did not drain before the shutdown deadline: publisherId={}",
                        properties.publisherId());
            }
        } catch (InterruptedException exception) {
            publicationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Trade Outbox publisher shutdown was interrupted: publisherId={}",
                    properties.publisherId());
        }
    }

    static String conciseError(Throwable exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (rootCause != exception) {
            message += " -> " + rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
