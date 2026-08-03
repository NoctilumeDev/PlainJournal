package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.service.RefundService;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.payment.refund-consumer", name = "enabled", havingValue = "true")
public class RefundRequestedConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestedConsumer.class);

    private final RefundEventConsumerProperties properties;
    private final RefundService refundService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public RefundRequestedConsumer(
            RefundEventConsumerProperties properties,
            RefundService refundService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.refundService = refundService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.payment.refund-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                RefundRequestedCommand command;
                try {
                    command = parse(message);
                } catch (Exception exception) {
                    failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
                    active.ack(message);
                    log.error("RefundRequested payload requires attention: messageId={}",
                            message.getMessageId(), exception);
                    continue;
                }
                try {
                    refundService.createFromRefundRequested(command);
                    failureRecorder.markRecovered(message, properties.consumerGroup());
                    active.ack(message);
                } catch (Exception exception) {
                    boolean terminal = failureRecorder.record(
                            message, properties.consumerGroup(), exception);
                    active.ack(message);
                    if (terminal) {
                        log.error("RefundRequested moved to the local compensation queue: messageId={}",
                                message.getMessageId(), exception);
                    } else {
                        log.warn("RefundRequested processing failed; durable MySQL retry now owns "
                                        + "recovery: messageId={}",
                                message.getMessageId(), exception);
                    }
                }
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    private RefundRequestedCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        refundService.createFromRefundRequested(
                parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private RefundRequestedCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported RefundRequested payload version");
        }
        if (!"RefundRequested".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected refund event type");
        }
        JsonNode payload = envelope.path("payload");
        return new RefundRequestedCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "afterSaleNo"),
                requiredText(payload, "orderNo"),
                requiredPositiveLong(payload, "userId"),
                requiredAmount(payload, "refundAmount"));
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
                                new FilterExpression("RefundRequested", FilterExpressionType.TAG)))
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
                    log.debug("Refund consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Refund event consumer is unavailable; payment service remains online", exception);
        } else {
            log.debug("Refund event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
