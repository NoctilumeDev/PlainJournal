package com.ecommerce.marketing.infrastructure.messaging;

import com.ecommerce.marketing.application.model.OrderLifecycleCommand;
import com.ecommerce.marketing.application.service.OrderLifecycleHandler;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.marketing.order-consumer", name = "enabled", havingValue = "true")
public class OrderLifecycleConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleConsumer.class);
    private static final Set<String> EVENT_TYPES = Set.of("OrderPaid", "OrderCanceled", "OrderClosed");

    private final OrderEventConsumerProperties properties;
    private final OrderLifecycleHandler handler;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public OrderLifecycleConsumer(
            OrderEventConsumerProperties properties,
            OrderLifecycleHandler handler,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.marketing.order-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                try {
                    handler.handle(parse(message));
                    active.ack(message);
                } catch (IllegalArgumentException exception) {
                    failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
                    active.ack(message);
                    log.error("Order lifecycle payload requires attention: messageId={}",
                            message.getMessageId(), exception);
                } catch (Exception exception) {
                    boolean terminal = failureRecorder.record(
                            message, properties.consumerGroup(), exception);
                    active.ack(message);
                    if (terminal) {
                        log.error("Order lifecycle processing exhausted retries: messageId={}",
                                message.getMessageId(), exception);
                    } else {
                        log.warn("Order lifecycle processing failed; durable MySQL retry now owns "
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

    private OrderLifecycleCommand parse(MessageView message) throws Exception {
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

    private OrderLifecycleCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported order lifecycle payload version");
        }
        String eventType = requiredText(envelope, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unexpected order event type");
        }
        return new OrderLifecycleCommand(requiredText(envelope, "eventId"), eventType,
                requiredText(envelope.path("payload"), "orderNo"));
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
                        .setEndpoints(properties.endpoints()).enableSsl(false).build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(properties.topic(),
                                new FilterExpression(
                                        "OrderPaid||OrderCanceled||OrderClosed",
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
                } catch (Exception ignored) {
                    log.debug("Order lifecycle consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Order event consumer is unavailable; marketing service remains online", exception);
        } else {
            log.debug("Order event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
