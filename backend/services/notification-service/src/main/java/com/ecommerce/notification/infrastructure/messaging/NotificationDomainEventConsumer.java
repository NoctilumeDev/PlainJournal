package com.ecommerce.notification.infrastructure.messaging;

import com.ecommerce.notification.application.model.NotificationModels.DomainEvent;
import com.ecommerce.notification.application.service.NotificationApplicationService;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.notification.infrastructure.config.NotificationEventConsumerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.notification.events",
        name = "enabled",
        havingValue = "true")
public class NotificationDomainEventConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDomainEventConsumer.class);
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "PaymentSucceeded",
            "RefundSucceeded",
            "ShipmentDispatched",
            "ShipmentSigned");
    private static final Map<String, EventSource> EVENT_SOURCES = Map.of(
            "PaymentSucceeded",
            new EventSource("payment-service", "PaymentOrder", "paymentNo"),
            "RefundSucceeded",
            new EventSource("payment-service", "RefundOrder", "refundNo"),
            "ShipmentDispatched",
            new EventSource("fulfillment-service", "FulfillmentOrder", "fulfillmentNo"),
            "ShipmentSigned",
            new EventSource("fulfillment-service", "FulfillmentOrder", "fulfillmentNo"));

    private final NotificationEventConsumerProperties properties;
    private final NotificationApplicationService service;
    private final NotificationConsumerFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public NotificationDomainEventConsumer(
            NotificationEventConsumerProperties properties,
            NotificationApplicationService service,
            NotificationConsumerFailureRecorder failureRecorder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.service = service;
        this.failureRecorder = failureRecorder;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.notification.events.initial-delay:2000}",
            fixedDelayString = "${ecommerce.notification.events.fixed-delay:500}")
    public void consume() {
        try {
            SimpleConsumer active = consumer();
            for (MessageView message : active.receive(
                    properties.batchSize(),
                    properties.invisibleDuration())) {
                processMessage(message, active);
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    void processMessage(MessageView message, SimpleConsumer active) throws Exception {
        DomainEvent event;
        try {
            event = parse(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Invalid notification source event was durably recorded and acknowledged: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            service.acceptDomainEvent(event, properties.consumerGroup());
            failureRecorder.markRecovered(message, properties.consumerGroup());
            active.ack(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Unsupported notification event payload was durably recorded and acknowledged: "
                            + "messageId={}, eventId={}",
                    message.getMessageId(), event.eventId(), exception);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message,
                    properties.consumerGroup(),
                    exception);
            active.ack(message);
            if (terminal) {
                log.error("Notification event processing exhausted retries and requires attention: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            } else {
                log.warn("Notification event processing failed; durable MySQL retry now owns "
                                + "recovery: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            }
        }
    }

    private DomainEvent parse(MessageView message) {
        return parseBytes(body(message.getBody()));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) {
        service.acceptDomainEvent(parseText(rawPayload), properties.consumerGroup());
    }

    private DomainEvent parseBytes(byte[] payload) {
        try {
            return parseEnvelope(objectMapper.readTree(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Notification source event JSON is invalid", exception);
        }
    }

    private DomainEvent parseText(String payload) {
        try {
            return parseEnvelope(objectMapper.readTree(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Notification source event JSON is invalid", exception);
        }
    }

    private DomainEvent parseEnvelope(JsonNode envelope) {
            if (envelope.path("payloadVersion").asInt(-1) != 1) {
                throw new IllegalArgumentException("Unsupported notification event payload version");
            }
            String eventType = requiredText(envelope, "eventType");
            if (!SUPPORTED_EVENTS.contains(eventType)) {
                throw new IllegalArgumentException(
                        "Unsupported notification event type: " + eventType);
            }
            JsonNode payload = envelope.path("payload");
            validateSourceIdentity(envelope, payload, eventType);
            long userId = requiredPositiveLong(payload, "userId");
            return new DomainEvent(
                    requiredText(envelope, "eventId"),
                    eventType,
                    userId,
                    payload);
    }

    private void validateSourceIdentity(
            JsonNode envelope,
            JsonNode payload,
            String eventType) {
        EventSource source = EVENT_SOURCES.get(eventType);
        String producer = requiredText(envelope, "producer");
        String aggregateType = requiredText(envelope, "aggregateType");
        String aggregateId = requiredText(envelope, "aggregateId");
        requiredNonNegativeLong(envelope, "aggregateVersion");
        String payloadAggregateId = requiredText(payload, source.payloadAggregateIdField());
        if (!source.producer().equals(producer)
                || !source.aggregateType().equals(aggregateType)
                || !aggregateId.equals(payloadAggregateId)) {
            throw new IllegalArgumentException(
                    "Notification event source identity does not match its contract");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing notification event field: " + field);
        }
        return value;
    }

    private long requiredPositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong() && value.longValue() > 0) {
            return value.longValue();
        }
        if (value != null && value.isTextual() && value.textValue().matches("[0-9]+")) {
            long parsed = Long.parseLong(value.textValue());
            if (parsed > 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Missing or invalid notification event field: " + field);
    }

    private long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong() && value.longValue() >= 0) {
            return value.longValue();
        }
        if (value != null && value.isTextual() && value.textValue().matches("[0-9]+")) {
            return Long.parseLong(value.textValue());
        }
        throw new IllegalArgumentException("Missing or invalid notification event field: " + field);
    }

    private byte[] body(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private SimpleConsumer consumer() throws Exception {
        SimpleConsumer current = consumer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (consumer == null) {
                ClientConfiguration configuration = ClientConfiguration.newBuilder()
                        .setEndpoints(properties.endpoints())
                        .enableSsl(false)
                        .build();
                Map<String, FilterExpression> subscriptions = new LinkedHashMap<>();
                subscriptions.put(
                        properties.paymentTopic(),
                        new FilterExpression(
                                "PaymentSucceeded||RefundSucceeded",
                                FilterExpressionType.TAG));
                subscriptions.put(
                        properties.logisticsTopic(),
                        new FilterExpression(
                                "ShipmentDispatched||ShipmentSigned",
                                FilterExpressionType.TAG));
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(subscriptions)
                        .build();
            }
            return consumer;
        }
    }

    private void resetConsumer() {
        synchronized (this) {
            SimpleConsumer current = consumer;
            consumer = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception exception) {
                    log.debug("Notification consumer close failed", exception);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Notification event consumer is unavailable; source events remain recoverable",
                    exception);
        } else {
            log.debug("Notification event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }

    private record EventSource(
            String producer,
            String aggregateType,
            String payloadAggregateIdField) {
    }
}
