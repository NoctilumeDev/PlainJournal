package com.ecommerce.catalog.infrastructure.messaging;

import com.ecommerce.catalog.application.model.ReviewModels.OrderCompletedEvent;
import com.ecommerce.catalog.application.model.ReviewModels.OrderLineSnapshot;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.catalog.infrastructure.config.ReviewEventConsumerProperties;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.catalog.review-events",
        name = "enabled",
        havingValue = "true")
public class OrderCompletedConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCompletedConsumer.class);

    private final ReviewEventConsumerProperties properties;
    private final ProductReviewService service;
    private final CatalogConsumerFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public OrderCompletedConsumer(
            ReviewEventConsumerProperties properties,
            ProductReviewService service,
            CatalogConsumerFailureRecorder failureRecorder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.service = service;
        this.failureRecorder = failureRecorder;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.catalog.review-events.initial-delay:2000}",
            fixedDelayString = "${ecommerce.catalog.review-events.fixed-delay:500}")
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
        OrderCompletedEvent event;
        try {
            event = parse(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Invalid OrderCompleted event was recorded and acknowledged: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            service.acceptOrderCompleted(event, properties.consumerGroup());
            failureRecorder.markRecovered(message, properties.consumerGroup());
            active.ack(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Invalid review eligibility payload was recorded and acknowledged: "
                            + "messageId={}, eventId={}",
                    message.getMessageId(), event.eventId(), exception);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message,
                    properties.consumerGroup(),
                    exception);
            active.ack(message);
            if (terminal) {
                log.error("OrderCompleted review processing exhausted retries: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            } else {
                log.warn("OrderCompleted review processing failed; durable MySQL retry now owns "
                                + "recovery: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            }
        }
    }

    private OrderCompletedEvent parse(MessageView message) {
        return parseBytes(body(message.getBody()));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) {
        service.acceptOrderCompleted(parseText(rawPayload), properties.consumerGroup());
    }

    private OrderCompletedEvent parseBytes(byte[] payload) {
        try {
            return parseEnvelope(objectMapper.readTree(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "OrderCompleted event JSON is invalid",
                    exception);
        }
    }

    private OrderCompletedEvent parseText(String payload) {
        try {
            return parseEnvelope(objectMapper.readTree(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "OrderCompleted event JSON is invalid",
                    exception);
        }
    }

    private OrderCompletedEvent parseEnvelope(JsonNode envelope) {
            if (envelope.path("payloadVersion").asInt(-1) != 1) {
                throw new IllegalArgumentException(
                        "Unsupported OrderCompleted payload version");
            }
            if (!"OrderCompleted".equals(requiredText(envelope, "eventType"))) {
                throw new IllegalArgumentException(
                        "Unsupported catalog review event type");
            }
            JsonNode payload = envelope.path("payload");
            String orderNo = requiredText(payload, "orderNo");
            validateSourceIdentity(envelope, orderNo);
            JsonNode itemNodes = payload.path("items");
            if (!itemNodes.isArray() || itemNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "OrderCompleted items are missing");
            }
            ArrayList<OrderLineSnapshot> items = new ArrayList<>();
            for (JsonNode item : itemNodes) {
                items.add(new OrderLineSnapshot(
                        requiredPositiveInt(item, "lineNo"),
                        requiredPositiveLong(item, "productId"),
                        requiredPositiveLong(item, "skuId"),
                        requiredText(item, "productTitle"),
                        requiredText(item, "skuCode"),
                        requiredText(item, "skuName"),
                        requiredText(item, "specJson"),
                        optionalText(item, "imageObjectKey"),
                        requiredPositiveLong(item, "quantity")));
            }
            return new OrderCompletedEvent(
                    requiredText(envelope, "eventId"),
                    orderNo,
                    requiredPositiveLong(payload, "userId"),
                    Instant.parse(requiredText(envelope, "occurredAt")),
                    items);
    }

    private void validateSourceIdentity(JsonNode envelope, String orderNo) {
        String producer = requiredText(envelope, "producer");
        String aggregateType = requiredText(envelope, "aggregateType");
        String aggregateId = requiredText(envelope, "aggregateId");
        requiredNonNegativeLong(envelope, "aggregateVersion");
        if (!"trade-service".equals(producer)
                || !"TradeOrder".equals(aggregateType)
                || !orderNo.equals(aggregateId)) {
            throw new IllegalArgumentException(
                    "OrderCompleted source identity does not match its contract");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing OrderCompleted field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        long value = requiredPositiveLong(node, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "OrderCompleted integer field is too large: " + field);
        }
        return (int) value;
    }

    private long requiredPositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong() && value.longValue() > 0) {
            return value.longValue();
        }
        if (value != null && value.isTextual()
                && value.textValue().matches("[0-9]+")) {
            long parsed = Long.parseLong(value.textValue());
            if (parsed > 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException(
                "Missing or invalid OrderCompleted field: " + field);
    }

    private long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong() && value.longValue() >= 0) {
            return value.longValue();
        }
        if (value != null && value.isTextual()
                && value.textValue().matches("[0-9]+")) {
            return Long.parseLong(value.textValue());
        }
        throw new IllegalArgumentException(
                "Missing or invalid OrderCompleted field: " + field);
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
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.topic(),
                                new FilterExpression(
                                        "OrderCompleted",
                                        FilterExpressionType.TAG)))
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
                    log.debug("Catalog review consumer close failed", exception);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Catalog review event consumer is unavailable; "
                    + "Trade Outbox remains recoverable", exception);
        } else {
            log.debug("Catalog review event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
