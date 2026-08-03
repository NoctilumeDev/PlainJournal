package com.ecommerce.marketing.infrastructure.messaging;

import com.ecommerce.marketing.infrastructure.config.MarketingSchedulingConfig;
import com.ecommerce.marketing.infrastructure.observability.FlashSaleOutboxMetrics;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleOutboxEventEntity;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleOutboxEventMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.marketing.flash-sale-outbox",
        name = "enabled",
        havingValue = "true")
public class FlashSaleOutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleOutboxPublisherJob.class);

    private final FlashSaleOutboxEventMapper outboxMapper;
    private final FlashSaleEventPublisher publisher;
    private final FlashSaleOutboxProperties properties;
    private final FlashSaleOutboxMetrics metrics;
    private final Clock clock;

    public FlashSaleOutboxPublisherJob(
            FlashSaleOutboxEventMapper outboxMapper,
            FlashSaleEventPublisher publisher,
            FlashSaleOutboxProperties properties,
            FlashSaleOutboxMetrics metrics,
            Clock clock) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.marketing.flash-sale-outbox.fixed-delay:500}",
            scheduler = MarketingSchedulingConfig.OUTBOX_SCHEDULER)
    public void publishPendingEvents() {
        Instant now = outboxMapper.currentTime();
        for (FlashSaleOutboxEventEntity candidate
                : outboxMapper.selectClaimCandidates(now, properties.batchSize())) {
            publishClaimed(candidate, now);
        }
    }

    private void publishClaimed(FlashSaleOutboxEventEntity candidate, Instant claimedAt) {
        Instant claimUntil = claimedAt.plus(properties.leaseDuration());
        if (outboxMapper.claim(
                candidate.getId(),
                properties.publisherId(),
                candidate.getAttempts(),
                claimedAt,
                claimUntil) != 1) {
            metrics.claimContended();
            return;
        }
        FlashSaleOutboxEventEntity event =
                outboxMapper.selectClaimed(
                        candidate.getId(),
                        properties.publisherId(),
                        claimedAt);
        if (event == null) {
            metrics.claimContended();
            return;
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
            }
        } catch (Exception exception) {
            Instant failedAt = outboxMapper.currentTime();
            int rows = outboxMapper.markFailed(
                    event.getId(),
                    properties.publisherId(),
                    failedAt.plus(properties.retryDelay()),
                    conciseError(exception),
                    failedAt);
            if (rows == 1) {
                metrics.publicationFailed(sample);
            } else {
                metrics.publicationStateConflict(sample);
            }
            log.warn("Flash-sale event publication failed and remains in outbox: eventId={}, type={}",
                    event.getId(), event.getEventType(), exception);
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
