package com.ecommerce.fulfillment.infrastructure.messaging;

import com.ecommerce.fulfillment.application.port.DomainEventPublisher;
import com.ecommerce.fulfillment.infrastructure.config.FulfillmentSchedulingConfig;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "ecommerce.fulfillment.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private final OutboxEventMapper outboxMapper;
    private final DomainEventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxMetrics metrics;

    public OutboxPublisherJob(OutboxEventMapper outboxMapper, DomainEventPublisher publisher,
                              OutboxProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.metrics = new OutboxMetrics(meterRegistry, "fulfillment-service",
                outboxMapper::countUnpublished, outboxMapper::selectOldestUnpublishedCreatedAt, clock);
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.fulfillment.outbox.fixed-delay:2000}",
            scheduler = FulfillmentSchedulingConfig.OUTBOX_SCHEDULER)
    public void publishPendingEvents() {
        Instant now = outboxMapper.currentTime();
        metrics.staleClaimsRecovered(outboxMapper.resetStaleClaims(now, now));
        for (OutboxEventEntity event : outboxMapper.selectPublishable(now, properties.batchSize())) {
            if (outboxMapper.claim(
                    event.getId(),
                    properties.publisherId(),
                    event.getAttempts(),
                    now,
                    now.plus(properties.leaseDuration())) != 1) {
                metrics.claimContended();
                continue;
            }
            Timer.Sample sample = metrics.startPublication();
            try {
                publisher.publish(event.getId(), event.getEventType(), event.getPayload());
                Instant completedAt = outboxMapper.currentTime();
                if (outboxMapper.markPublished(
                        event.getId(), properties.publisherId(), completedAt) == 1) {
                    metrics.publicationSucceeded(sample);
                } else {
                    metrics.publicationStateConflict(sample);
                    log.warn("Fulfillment event was sent but its publishing claim was lost: eventId={}, type={}",
                            event.getId(), event.getEventType());
                }
            } catch (Exception exception) {
                Instant failedAt = outboxMapper.currentTime();
                try {
                    if (outboxMapper.markFailed(
                            event.getId(),
                            properties.publisherId(),
                            failedAt.plus(properties.retryDelay()),
                            conciseError(exception),
                            failedAt) == 1) {
                        metrics.publicationFailed(sample);
                    } else {
                        metrics.publicationStateConflict(sample);
                        log.warn("Fulfillment event failed after its publishing lease was lost: "
                                        + "eventId={}, type={}",
                                event.getId(), event.getEventType());
                    }
                } catch (RuntimeException persistenceException) {
                    metrics.publicationFailed(sample);
                    throw persistenceException;
                }
                log.warn("Fulfillment event publication failed and remains in outbox: eventId={}, type={}",
                        event.getId(), event.getEventType());
            }
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
