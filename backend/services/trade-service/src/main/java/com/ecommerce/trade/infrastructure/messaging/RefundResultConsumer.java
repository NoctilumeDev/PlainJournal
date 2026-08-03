package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.model.TradeModels.RefundEventCommand;
import com.ecommerce.trade.application.service.AfterSaleService;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import io.micrometer.tracing.Span;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.refund-result-consumer", name = "enabled", havingValue = "true")
public class RefundResultConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundResultConsumer.class);
    private static final String TAGS = "RefundSucceeded||RefundFailed";
    private static final Set<String> EVENT_TYPES = Set.of("RefundSucceeded", "RefundFailed");

    private final RefundResultConsumerProperties properties;
    private final AfterSaleService afterSaleService;
    private final TradeOrderService orderService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final MessagingTracing messagingTracing;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public RefundResultConsumer(
            RefundResultConsumerProperties properties,
            AfterSaleService afterSaleService,
            TradeOrderService orderService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder,
            MessagingTracing messagingTracing) {
        this.properties = properties;
        this.afterSaleService = afterSaleService;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
        this.messagingTracing = messagingTracing;
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.refund-result-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                processMessage(message, active);
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    void processMessage(MessageView message, SimpleConsumer active) throws Exception {
        RefundEventCommand command;
        try {
            command = parse(message);
        } catch (Exception exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Refund result payload requires attention: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            messagingTracing.inSpan(
                    "rocketmq consume " + command.eventType(),
                    Span.Kind.CONSUMER,
                    message.getProperties(),
                    Map.of(
                            "messaging.system", "rocketmq",
                            "messaging.destination.name", properties.topic(),
                            "messaging.operation", "process",
                            "messaging.message.id", message.getMessageId().toString(),
                            "messaging.event.type", command.eventType()),
                    () -> {
                        apply(command);
                        failureRecorder.markRecovered(message, properties.consumerGroup());
                        active.ack(message);
                    });
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message, properties.consumerGroup(), exception);
            active.ack(message);
            if (terminal) {
                log.error("Refund result event moved to the local compensation queue: messageId={}",
                        message.getMessageId(), exception);
            } else {
                log.warn("Refund result event failed; durable MySQL retry now owns "
                                + "recovery: messageId={}",
                        message.getMessageId(), exception);
            }
        }
    }

    private RefundEventCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        apply(parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private RefundEventCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported refund result payload version");
        }
        String eventType = requiredText(envelope, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unexpected refund result event type");
        }
        JsonNode payload = envelope.path("payload");
        return new RefundEventCommand(
                requiredText(envelope, "eventId"), eventType, requiredText(payload, "refundNo"),
                requiredText(payload, "afterSaleNo"), requiredText(payload, "orderNo"),
                requiredText(payload, "paymentNo"), requiredPositiveLong(payload, "userId"),
                requiredAmount(payload, "amount"));
    }

    private void apply(RefundEventCommand command) {
        if (command.afterSaleNo().startsWith("PEX-")) {
            orderService.applyPaymentExceptionRefundEvent(command);
        } else {
            afterSaleService.applyRefundEvent(command);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.asText();
    }

    private Long requiredPositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Missing or invalid event field: " + field);
        }
        return value.longValue();
    }

    private BigDecimal requiredAmount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) {
            throw new IllegalArgumentException("Missing or invalid event field: " + field);
        }
        return value.decimalValue();
    }

    private byte[] readBody(ByteBuffer source) {
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
                        .setEndpoints(properties.endpoints()).enableSsl(false).build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(properties.topic(),
                                new FilterExpression(TAGS, FilterExpressionType.TAG)))
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
                } catch (Exception ignored) {
                    log.debug("Refund result consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Refund result consumer unavailable; trade service remains online", exception);
        } else {
            log.debug("Refund result consumer reconnect failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
