package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.application.port.ChatEventPublisher;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMemberMapper;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import com.ecommerce.chat.infrastructure.realtime.RedisChatPresenceStore;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatStoredEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatStoredEventConsumer.class);

    private final ChatRealtimeProperties properties;
    private final ConversationMemberMapper memberMapper;
    private final RedisChatPresenceStore presenceStore;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public ChatStoredEventConsumer(
            ChatRealtimeProperties properties,
            ConversationMemberMapper memberMapper,
            RedisChatPresenceStore presenceStore,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.memberMapper = memberMapper;
        this.presenceStore = presenceStore;
        this.publisher = publisher;
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
        StoredEvent event;
        try {
            event = parse(message);
        } catch (IllegalArgumentException exception) {
            failureRecorder.recordTerminal(
                    message,
                    properties.dispatcherConsumerGroup(),
                    exception);
            active.ack(message);
            log.error("Invalid ChatMessageStored event requires attention and was acknowledged "
                            + "after durable failure recording: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            dispatch(event);
            failureRecorder.markRecovered(message, properties.dispatcherConsumerGroup());
            active.ack(message);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(
                    message,
                    properties.dispatcherConsumerGroup(),
                    exception);
            if (terminal) {
                active.ack(message);
                log.error("ChatMessageStored dispatch exhausted retries and requires attention: "
                                + "messageId={}, eventId={}",
                        message.getMessageId(), event.eventId(), exception);
            } else {
                active.ack(message);
                log.warn("ChatMessageStored dispatch failed; durable MySQL retry now owns "
                                + "recovery: messageId={}, eventId={}",
                        message.getMessageId(),
                        event.eventId(),
                        exception);
            }
        }
    }

    void retryPayload(String rawPayload) {
        dispatch(parseRawPayload(rawPayload));
    }

    private void dispatch(StoredEvent event) {
        for (Long recipientId : memberMapper.selectRecipientIds(
                event.conversationId(),
                event.senderId())) {
            Set<String> nodes = presenceStore.onlineNodes(recipientId);
            for (String nodeId : nodes) {
                publishDelivery(event, recipientId, nodeId);
            }
        }
    }

    private StoredEvent parseRawPayload(String rawPayload) {
        try {
            return parseEnvelope(objectMapper.readTree(rawPayload));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Chat stored event JSON is invalid", exception);
        }
    }

    private void publishDelivery(StoredEvent event, Long recipientId, String nodeId) {
        String deliveryEventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", deliveryEventId);
        envelope.put("eventType", "ChatDeliveryRequested");
        envelope.put("payloadVersion", 1);
        envelope.put("occurredAt", event.occurredAt());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceEventId", event.eventId());
        payload.put("messageId", event.messageId());
        payload.put("conversationId", event.conversationId());
        payload.put("messageSequence", event.messageSequence());
        payload.put("recipientId", recipientId);
        payload.put("targetNodeId", nodeId);
        envelope.put("payload", payload);
        try {
            publisher.publish(
                    properties.deliveryTopic(),
                    deliveryEventId,
                    properties.nodeTag(nodeId),
                    objectMapper.writeValueAsString(envelope)).join();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to publish targeted chat delivery for node " + nodeId,
                    exception);
        }
    }

    private StoredEvent parse(MessageView message) {
        try {
            return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Chat stored event JSON is invalid", exception);
        }
    }

    private StoredEvent parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1
                || !"ChatMessageStored".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unsupported chat stored event contract");
        }
        JsonNode payload = envelope.path("payload");
        return new StoredEvent(
                requiredText(envelope, "eventId"),
                requiredInstant(envelope, "occurredAt"),
                requiredPositiveLong(payload, "messageId"),
                requiredPositiveLong(payload, "conversationId"),
                requiredPositiveLong(payload, "messageSequence"),
                requiredPositiveLong(payload, "senderId"));
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

    private Instant requiredInstant(JsonNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Missing or invalid event field: " + field, exception);
        }
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
                        .setConsumerGroup(properties.dispatcherConsumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.sourceTopic(),
                                new FilterExpression(
                                        "ChatMessageStored",
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
                    log.debug("Chat dispatcher consumer close failed", exception);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Chat stored-event dispatcher is unavailable; persisted messages remain recoverable",
                    exception);
        } else {
            log.debug("Chat stored-event dispatcher reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }

    private record StoredEvent(
            String eventId,
            Instant occurredAt,
            Long messageId,
            Long conversationId,
            Long messageSequence,
            Long senderId) {
    }
}
