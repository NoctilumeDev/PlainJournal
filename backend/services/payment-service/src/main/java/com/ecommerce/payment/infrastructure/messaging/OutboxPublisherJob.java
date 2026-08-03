package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.application.port.DomainEventPublisher;
import com.ecommerce.payment.infrastructure.config.PaymentSchedulingConfig;
import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ecommerce.payment.outbox", name = "enabled", havingValue = "true")
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private final OutboxEventMapper outboxMapper;
    private final DomainEventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;
    private final OutboxMetrics metrics;
    private final ObjectMapper objectMapper;
    private final MessagingTracing messagingTracing;

    public OutboxPublisherJob(
            OutboxEventMapper outboxMapper,
            DomainEventPublisher publisher,
            OutboxProperties properties,
            Clock clock,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            MessagingTracing messagingTracing) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.metrics = new OutboxMetrics(meterRegistry, "payment-service",
                outboxMapper::countUnpublished, outboxMapper::selectOldestUnpublishedCreatedAt, clock);
        this.objectMapper = objectMapper;
        this.messagingTracing = messagingTracing;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.payment.outbox.fixed-delay:2000}",
            scheduler = PaymentSchedulingConfig.OUTBOX_SCHEDULER)
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
                messagingTracing.inSpan(
                        "rocketmq publish " + event.getEventType(),
                        Span.Kind.PRODUCER,
                        readTraceContext(event),
                        Map.of(
                                "messaging.system", "rocketmq",
                                "messaging.destination.name", properties.topic(),
                                "messaging.operation", "publish",
                                "messaging.message.id", event.getId(),
                        "messaging.event.type", event.getEventType()),
                        () -> publisher.publish(event.getId(), event.getEventType(), event.getPayload()));
                Instant completedAt = outboxMapper.currentTime();
                if (outboxMapper.markPublished(
                        event.getId(), properties.publisherId(), completedAt) == 1) {
                    metrics.publicationSucceeded(sample);
                } else {
                    metrics.publicationStateConflict(sample);
                    log.warn("Payment event was sent but its publishing claim was lost: eventId={}, type={}",
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
                        log.warn("Payment event failed after its publishing lease was lost: eventId={}, type={}",
                                event.getId(), event.getEventType());
                    }
                } catch (RuntimeException persistenceException) {
                    metrics.publicationFailed(sample);
                    throw persistenceException;
                }
                log.warn("Payment event publication failed and remains in outbox: eventId={}, type={}",
                        event.getId(), event.getEventType());
            }
        }
    }

    private Map<String, String> readTraceContext(OutboxEventEntity event) {
        try {
            JsonNode traceContext = objectMapper.readTree(event.getPayload()).path("traceContext");
            if (!traceContext.isObject()) {
                return Map.of();
            }
            Map<String, String> carrier = new LinkedHashMap<>();
            traceContext.properties().forEach(field -> {
                if (field.getValue().isTextual() && !field.getValue().asText().isBlank()) {
                    carrier.put(field.getKey(), field.getValue().asText());
                }
            });
            return carrier;
        } catch (JsonProcessingException exception) {
            log.warn("Payment outbox trace context is unreadable; publishing as a new trace: eventId={}",
                    event.getId());
            return Map.of();
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
