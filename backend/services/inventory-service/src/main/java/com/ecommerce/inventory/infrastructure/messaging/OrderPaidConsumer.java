package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.application.service.OrderPaidHandler;
import com.ecommerce.inventory.application.service.OrderPaidHandler.OrderPaidCommand;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.inventory.order-consumer", name = "enabled", havingValue = "true")
public class OrderPaidConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidConsumer.class);

    private final OrderEventConsumerProperties properties;
    private final OrderPaidHandler handler;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public OrderPaidConsumer(
            OrderEventConsumerProperties properties,
            OrderPaidHandler handler,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.inventory.order-consumer.fixed-delay:1000}")
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
        OrderPaidCommand command;
        try {
            command = parse(message);
        } catch (Exception exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("OrderPaid payload is not retryable and requires attention: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            handler.handle(command);
            failureRecorder.markRecovered(message, properties.consumerGroup());
            active.ack(message);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(message, properties.consumerGroup(), exception);
            active.ack(message);
            if (terminal) {
                log.error("OrderPaid processing exhausted retries and requires attention: messageId={}",
                        message.getMessageId(), exception);
            } else {
                log.warn("OrderPaid processing failed; durable MySQL retry now owns recovery: "
                                + "messageId={}",
                        message.getMessageId(), exception);
            }
        }
    }

    private OrderPaidCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        handler.handle(parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private OrderPaidCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported OrderPaid payload version");
        }
        if (!"OrderPaid".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected order event type");
        }
        JsonNode payload = envelope.path("payload");
        return new OrderPaidCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "orderNo"),
                requiredText(payload, "reservationNo"));
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.asText();
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
                        .setEndpoints(properties.endpoints())
                        .enableSsl(false)
                        .build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.topic(),
                                new FilterExpression("OrderPaid", FilterExpressionType.TAG)))
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
                    log.debug("Order consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Order event consumer is unavailable; inventory service remains online", exception);
        } else {
            log.debug("Order event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
