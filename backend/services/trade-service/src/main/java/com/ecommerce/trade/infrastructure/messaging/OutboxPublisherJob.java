package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private final OutboxEventMapper outboxMapper;
    private final DomainEventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPublisherJob(
            OutboxEventMapper outboxMapper,
            DomainEventPublisher publisher,
            OutboxProperties properties,
            Clock clock) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.outbox.fixed-delay:2000}")
    public void publishPendingEvents() {
        Instant now = clock.instant();
        outboxMapper.resetStaleClaims(now.minus(CLAIM_TIMEOUT), now);
        for (OutboxEventEntity event : outboxMapper.selectPublishable(now, properties.batchSize())) {
            if (outboxMapper.claim(event.getId(), now) != 1) {
                continue;
            }
            try {
                publisher.publish(event.getId(), event.getEventType(), event.getPayload());
                outboxMapper.markPublished(event.getId(), clock.instant());
            } catch (Exception exception) {
                Instant failedAt = clock.instant();
                outboxMapper.markFailed(event.getId(), failedAt.plus(properties.retryDelay()),
                        conciseError(exception), failedAt);
                log.warn("Trade event publication failed and remains in outbox: eventId={}, type={}",
                        event.getId(), event.getEventType());
            }
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
