package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.application.port.ChatEventPublisher;
import com.ecommerce.chat.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@ConditionalOnProperty(
        prefix = "ecommerce.chat.outbox",
        name = "enabled",
        havingValue = "true")
public class ChatOutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(ChatOutboxPublisherJob.class);

    private final OutboxEventMapper outboxMapper;
    private final ChatOutboxCompletionService completionService;
    private final ChatEventPublisher publisher;
    private final ChatOutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OutboxMetrics metrics;

    public ChatOutboxPublisherJob(
            OutboxEventMapper outboxMapper,
            ChatOutboxCompletionService completionService,
            ChatEventPublisher publisher,
            ChatOutboxProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.completionService = completionService;
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = new OutboxMetrics(
                meterRegistry,
                "chat-service",
                outboxMapper::countUnpublished,
                outboxMapper::selectOldestUnpublishedCreatedAt,
                clock);
    }

    @Scheduled(
            initialDelayString = "${ecommerce.chat.outbox.initial-delay:2000}",
            fixedDelayString = "${ecommerce.chat.outbox.fixed-delay:1000}")
    public void publishPendingEvents() {
        Instant now = outboxMapper.currentTime();
        metrics.staleClaimsRecovered(outboxMapper.resetStaleClaims(now));
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
            publishClaimed(event);
        }
    }

    private void publishClaimed(OutboxEventEntity event) {
        Timer.Sample sample = metrics.startPublication();
        try {
            Long messageId = messageId(event.getPayload());
            publisher.publish(
                    event.getDestinationTopic(),
                    event.getId(),
                    event.getEventType(),
                    event.getPayload()).join();
            Instant completedAt = outboxMapper.currentTime();
            if (completionService.markPublished(
                    event.getId(),
                    properties.publisherId(),
                    messageId,
                    completedAt)) {
                metrics.publicationSucceeded(sample);
            } else {
                metrics.publicationStateConflict(sample);
                log.warn("Chat event was sent but its publishing lease was lost: eventId={}, type={}",
                        event.getId(), event.getEventType());
            }
        } catch (Exception exception) {
            Instant failedAt = outboxMapper.currentTime();
            if (outboxMapper.markFailed(
                    event.getId(),
                    properties.publisherId(),
                    failedAt.plus(properties.retryDelay()),
                    conciseError(exception),
                    failedAt) == 1) {
                metrics.publicationFailed(sample);
            } else {
                metrics.publicationStateConflict(sample);
            }
            log.warn("Chat event publication failed and remains in Outbox: eventId={}, type={}, error={}",
                    event.getId(), event.getEventType(), conciseError(exception));
        }
    }

    private Long messageId(String payload) throws Exception {
        JsonNode value = objectMapper.readTree(payload).path("payload").path("messageId");
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) {
            return value.longValue();
        }
        if (value.isTextual() && value.textValue().matches("[0-9]+")) {
            long parsed = Long.parseLong(value.textValue());
            if (parsed > 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Chat Outbox payload does not contain a valid messageId");
    }

    static String conciseError(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (root != exception) {
            message += " -> " + root.getClass().getSimpleName() + ": " + root.getMessage();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
