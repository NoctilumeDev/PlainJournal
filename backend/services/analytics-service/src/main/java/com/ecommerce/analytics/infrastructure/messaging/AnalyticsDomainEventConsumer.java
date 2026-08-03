package com.ecommerce.analytics.infrastructure.messaging;

import com.ecommerce.analytics.application.model.AnalyticsModels.DomainEvent;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductLine;
import com.ecommerce.analytics.application.service.AnalyticsApplicationService;
import com.ecommerce.analytics.infrastructure.config.AnalyticsEventConsumerProperties;
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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.analytics.events",
        name = "enabled",
        havingValue = "true")
public class AnalyticsDomainEventConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDomainEventConsumer.class);
    private static final Set<String> TRADE_EVENTS = Set.of(
            "OrderCreated",
            "OrderClosed",
            "OrderCompleted",
            "AfterSaleApplied");
    private static final Set<String> PAYMENT_EVENTS = Set.of(
            "PaymentSucceeded",
            "RefundSucceeded");
    private static final Map<String, EventSource> EVENT_SOURCES = Map.of(
            "OrderCreated",
            new EventSource("trade-service", "TradeOrder", "orderNo"),
            "OrderClosed",
            new EventSource("trade-service", "TradeOrder", "orderNo"),
            "OrderCompleted",
            new EventSource("trade-service", "TradeOrder", "orderNo"),
            "AfterSaleApplied",
            new EventSource("trade-service", "AfterSaleOrder", "afterSaleNo"),
            "PaymentSucceeded",
            new EventSource("payment-service", "PaymentOrder", "paymentNo"),
            "RefundSucceeded",
            new EventSource("payment-service", "RefundOrder", "refundNo"));
    private static final Set<String> SUPPORTED_EVENTS;

    static {
        Set<String> values = new LinkedHashSet<>(TRADE_EVENTS);
        values.addAll(PAYMENT_EVENTS);
        SUPPORTED_EVENTS = Set.copyOf(values);
    }

    private final AnalyticsEventConsumerProperties properties;
    private final AnalyticsApplicationService service;
    private final AnalyticsConsumerFailureRecorder failureRecorder;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public AnalyticsDomainEventConsumer(
            AnalyticsEventConsumerProperties properties,
            AnalyticsApplicationService service,
            AnalyticsConsumerFailureRecorder failureRecorder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.service = service;
        this.failureRecorder = failureRecorder;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.analytics.events.initial-delay:2000}",
            fixedDelayString = "${ecommerce.analytics.events.fixed-delay:500}")
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
            log.error("Invalid analytics source event was durably recorded and acknowledged: messageId={}",
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
            log.error("Conflicting analytics event was durably recorded and acknowledged: "
                            + "messageId={}, eventId={}",
                    message.getMessageId(), event.eventId(), exception);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message,
                    properties.consumerGroup(),
                    exception);
            active.ack(message);
            if (terminal) {
                log.error("Analytics event processing exhausted retries and requires attention: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            } else {
                log.warn("Analytics event processing failed; durable MySQL retry now owns recovery: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            }
        }
    }

    DomainEvent parse(MessageView message) {
        return parseBytes(bodyBytes(message.getBody()));
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
            throw new IllegalArgumentException("Analytics source event JSON is invalid", exception);
        }
    }

    private DomainEvent parseText(String payload) {
        try {
            return parseEnvelope(objectMapper.readTree(payload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Analytics source event JSON is invalid", exception);
        }
    }

    private DomainEvent parseEnvelope(JsonNode envelope) {
            if (envelope.path("payloadVersion").asInt(-1) != 1) {
                throw new IllegalArgumentException("Unsupported analytics payload version");
            }
            String eventType = requiredText(envelope, "eventType");
            if (!SUPPORTED_EVENTS.contains(eventType)) {
                throw new IllegalArgumentException(
                        "Unsupported analytics event type: " + eventType);
            }
            JsonNode payload = envelope.path("payload");
            String eventId = requiredText(envelope, "eventId");
            String producer = requiredText(envelope, "producer");
            String aggregateType = requiredText(envelope, "aggregateType");
            String aggregateId = requiredText(envelope, "aggregateId");
            long aggregateVersion = requiredNonNegativeLong(envelope, "aggregateVersion");
            validateSourceIdentity(
                    eventType,
                    producer,
                    aggregateType,
                    aggregateId,
                    payload);
            Instant occurredAt = Instant.parse(requiredText(envelope, "occurredAt"));
            long userId = requiredPositiveLong(payload, "userId");
            String orderNo = requiredText(payload, "orderNo");
            BigDecimal amount = amount(eventType, payload);
            List<ProductLine> lines = "OrderCompleted".equals(eventType)
                    ? productLines(payload.path("items"))
                    : List.of();
            String fingerprint = fingerprint(
                    eventType,
                    producer,
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    occurredAt,
                    userId,
                    orderNo,
                    amount,
                    lines);
            return new DomainEvent(
                    eventId,
                    eventType,
                    producer,
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    occurredAt,
                    userId,
                    orderNo,
                    amount,
                    fingerprint,
                    lines);
    }

    private List<ProductLine> productLines(JsonNode items) {
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "OrderCompleted analytics event requires product items");
        }
        List<ProductLine> lines = new ArrayList<>();
        Set<Integer> lineNumbers = new LinkedHashSet<>();
        for (JsonNode item : items) {
            int lineNo = requiredPositiveInt(item, "lineNo");
            if (!lineNumbers.add(lineNo)) {
                throw new IllegalArgumentException(
                        "Duplicate analytics product line number: " + lineNo);
            }
            lines.add(new ProductLine(
                    lineNo,
                    requiredPositiveLong(item, "productId"),
                    requiredPositiveLong(item, "skuId"),
                    requiredText(item, "productTitle"),
                    requiredText(item, "skuCode"),
                    requiredPositiveLong(item, "quantity"),
                    optionalMoney(item, "payableAmount")));
        }
        return List.copyOf(lines);
    }

    private BigDecimal amount(String eventType, JsonNode payload) {
        String field = switch (eventType) {
            case "OrderCreated", "OrderClosed", "OrderCompleted" -> "totalAmount";
            case "AfterSaleApplied" -> "refundAmount";
            case "PaymentSucceeded", "RefundSucceeded" -> "amount";
            default -> throw new IllegalArgumentException(
                    "Unsupported analytics amount event: " + eventType);
        };
        return requiredMoney(payload, field);
    }

    private void validateSourceIdentity(
            String eventType,
            String producer,
            String aggregateType,
            String aggregateId,
            JsonNode payload) {
        EventSource source = EVENT_SOURCES.get(eventType);
        String payloadAggregateId = requiredText(payload, source.payloadAggregateIdField());
        if (!source.producer().equals(producer)
                || !source.aggregateType().equals(aggregateType)
                || !aggregateId.equals(payloadAggregateId)) {
            throw new IllegalArgumentException(
                    "Analytics event source identity does not match its contract");
        }
    }

    private String fingerprint(
            String eventType,
            String producer,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            Instant occurredAt,
            long userId,
            String orderNo,
            BigDecimal amount,
            List<ProductLine> lines) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("eventType", eventType);
        canonical.put("producer", producer);
        canonical.put("aggregateType", aggregateType);
        canonical.put("aggregateId", aggregateId);
        canonical.put("aggregateVersion", aggregateVersion);
        canonical.put("occurredAt", occurredAt);
        canonical.put("userId", userId);
        canonical.put("orderNo", orderNo);
        canonical.put("amount", amount);
        canonical.put("productLines", lines);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Analytics event fingerprint could not be generated",
                    exception);
        }
    }

    private BigDecimal requiredMoney(JsonNode node, String field) {
        BigDecimal value = decimal(node.get(field));
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Missing or invalid analytics money field: " + field);
        }
        return value.setScale(2);
    }

    private BigDecimal optionalMoney(JsonNode node, String field) {
        JsonNode candidate = node.get(field);
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        return requiredMoney(node, field);
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.textValue());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing analytics event field: " + field);
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
        throw new IllegalArgumentException(
                "Missing or invalid analytics event field: " + field);
    }

    private long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToLong() && value.longValue() >= 0) {
            return value.longValue();
        }
        throw new IllegalArgumentException(
                "Missing or invalid analytics event field: " + field);
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.canConvertToInt() && value.intValue() > 0) {
            return value.intValue();
        }
        throw new IllegalArgumentException(
                "Missing or invalid analytics event field: " + field);
    }

    private byte[] bodyBytes(ByteBuffer source) {
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
                        properties.tradeTopic(),
                        new FilterExpression(
                                "OrderCreated||OrderClosed||OrderCompleted||AfterSaleApplied",
                                FilterExpressionType.TAG));
                subscriptions.put(
                        properties.paymentTopic(),
                        new FilterExpression(
                                "PaymentSucceeded||RefundSucceeded",
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
                    log.debug("Analytics consumer close failed", exception);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Analytics event consumer is unavailable; source events remain recoverable",
                    exception);
        } else {
            log.debug("Analytics event consumer reconnect attempt failed", exception);
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
