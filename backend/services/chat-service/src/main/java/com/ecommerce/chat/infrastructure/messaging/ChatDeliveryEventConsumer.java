package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeDeliveryService;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatDeliveryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatDeliveryEventConsumer.class);

    private final ChatRealtimeProperties properties;
    private final ChatRealtimeDeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public ChatDeliveryEventConsumer(
            ChatRealtimeProperties properties,
            ChatRealtimeDeliveryService deliveryService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.chat.realtime.initial-delay:2000}",
            fixedDelayString = "${ecommerce.chat.realtime.fixed-delay:500}")
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
        DeliveryEvent event;
        try {
            event = parse(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(
                    message,
                    properties.deliveryConsumerGroup(),
                    exception);
            active.ack(message);
            log.error("Invalid ChatDeliveryRequested event requires attention and was acknowledged "
                            + "after durable failure recording: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            deliver(event);
            failureRecorder.markRecovered(message, properties.deliveryConsumerGroup());
            active.ack(message);
        } catch (IOException exception) {
            failureRecorder.markRecovered(message, properties.deliveryConsumerGroup());
            active.ack(message);
            log.info("Chat delivery target became offline before socket write; MySQL receipt remains "
                            + "recoverable: messageId={}, recipientId={}, nodeId={}",
                    event.messageId(), event.recipientId(), properties.nodeId());
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message,
                    properties.deliveryConsumerGroup(),
                    exception);
            if (terminal) {
                active.ack(message);
                log.error("Targeted chat delivery exhausted retries and requires attention: "
                                + "messageId={}, recipientId={}, nodeId={}",
                        event.messageId(), event.recipientId(), properties.nodeId(), exception);
            } else {
                active.ack(message);
                log.warn("Targeted chat delivery failed; durable MySQL retry now owns "
                                + "recovery: messageId={}, recipientId={}, nodeId={}",
                        event.messageId(),
                        event.recipientId(),
                        properties.nodeId(),
                        exception);
            }
        }
    }

    void retryPayload(String rawPayload) throws IOException {
        deliver(parseRawPayload(rawPayload));
    }

    private void deliver(DeliveryEvent event) throws IOException {
        deliveryService.deliver(event.messageId(), event.recipientId());
    }

    private DeliveryEvent parseRawPayload(String rawPayload) {
        try {
            return parseEnvelope(objectMapper.readTree(rawPayload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Chat delivery event JSON is invalid", exception);
        }
    }

    private DeliveryEvent parse(MessageView message) {
        try {
            return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Chat delivery event JSON is invalid", exception);
        }
    }

    private DeliveryEvent parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1
                || !"ChatDeliveryRequested".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unsupported chat delivery event contract");
        }
        JsonNode payload = envelope.path("payload");
        DeliveryEvent event = new DeliveryEvent(
                requiredPositiveLong(payload, "messageId"),
                requiredPositiveLong(payload, "recipientId"),
                requiredText(payload, "targetNodeId"));
        if (!properties.nodeId().equals(event.targetNodeId())) {
            throw new IllegalArgumentException(
                    "Target node mismatch: expected " + properties.nodeId()
                            + ", actual " + event.targetNodeId());
        }
        return event;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + field);
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
        throw new IllegalArgumentException("Missing or invalid event field: " + field);
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
                        .setConsumerGroup(properties.deliveryConsumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.deliveryTopic(),
                                new FilterExpression(
                                        properties.nodeTag(),
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
                    log.debug("Chat delivery consumer close failed", exception);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Chat delivery consumer is unavailable; offline replay remains authoritative",
                    exception);
        } else {
            log.debug("Chat delivery consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }

    private record DeliveryEvent(
            Long messageId,
            Long recipientId,
            String targetNodeId) {
    }
}
