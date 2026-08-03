package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.application.port.ChatEventPublisher;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMemberMapper;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import com.ecommerce.chat.infrastructure.realtime.RedisChatPresenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatStoredEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsMalformedPayloadBeforeAcknowledgingPoisonMessage() throws Exception {
        ConversationMemberMapper memberMapper = mock(ConversationMemberMapper.class);
        RedisChatPresenceStore presenceStore = mock(RedisChatPresenceStore.class);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatStoredEventConsumer consumer = consumer(
                memberMapper,
                presenceStore,
                publisher,
                failureRecorder);
        MessageView message = message(Map.of(
                "eventId", "stored-invalid",
                "eventType", "ChatMessageStored",
                "payloadVersion", 99));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("chat-dispatcher-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(memberMapper, presenceStore, publisher);
    }

    @Test
    void acknowledgesAfterDurableRetryOwnershipIsEstablished() throws Exception {
        ConversationMemberMapper memberMapper = mock(ConversationMemberMapper.class);
        RedisChatPresenceStore presenceStore = mock(RedisChatPresenceStore.class);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatStoredEventConsumer consumer = consumer(
                memberMapper,
                presenceStore,
                publisher,
                failureRecorder);
        MessageView message = validMessage("stored-retry");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(memberMapper)
                .selectRecipientIds(2001L, 2002L);
        when(failureRecorder.record(
                eq(message),
                eq("chat-dispatcher-test"),
                any(IllegalStateException.class)))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(failureRecorder).record(
                eq(message),
                eq("chat-dispatcher-test"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    @Test
    void acknowledgesAfterRetryBudgetIsExhausted() throws Exception {
        ConversationMemberMapper memberMapper = mock(ConversationMemberMapper.class);
        RedisChatPresenceStore presenceStore = mock(RedisChatPresenceStore.class);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatStoredEventConsumer consumer = consumer(
                memberMapper,
                presenceStore,
                publisher,
                failureRecorder);
        MessageView message = validMessage("stored-terminal");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(memberMapper)
                .selectRecipientIds(2001L, 2002L);
        when(failureRecorder.record(
                eq(message),
                eq("chat-dispatcher-test"),
                any(IllegalStateException.class)))
                .thenReturn(true);

        consumer.processMessage(message, active);

        verify(active).ack(message);
    }

    @Test
    void successfulRedeliveryMarksFailureRecoveredBeforeAcknowledgement() throws Exception {
        ConversationMemberMapper memberMapper = mock(ConversationMemberMapper.class);
        RedisChatPresenceStore presenceStore = mock(RedisChatPresenceStore.class);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatStoredEventConsumer consumer = consumer(
                memberMapper,
                presenceStore,
                publisher,
                failureRecorder);
        MessageView message = validMessage("stored-recovered");
        SimpleConsumer active = mock(SimpleConsumer.class);
        when(memberMapper.selectRecipientIds(2001L, 2002L)).thenReturn(List.of());

        consumer.processMessage(message, active);

        verify(failureRecorder).markRecovered(message, "chat-dispatcher-test");
        verify(active).ack(message);
    }

    private ChatStoredEventConsumer consumer(
            ConversationMemberMapper memberMapper,
            RedisChatPresenceStore presenceStore,
            ChatEventPublisher publisher,
            ConsumerFailureRecorder failureRecorder) {
        return new ChatStoredEventConsumer(
                properties(),
                memberMapper,
                presenceStore,
                publisher,
                objectMapper,
                failureRecorder);
    }

    private MessageView validMessage(String eventId) throws Exception {
        return message(Map.of(
                "eventId", eventId,
                "eventType", "ChatMessageStored",
                "payloadVersion", 1,
                "occurredAt", "2026-07-23T00:00:00Z",
                "payload", Map.of(
                        "messageId", 2000L,
                        "conversationId", 2001L,
                        "messageSequence", 1L,
                        "senderId", 2002L)));
    }

    private MessageView message(Map<String, Object> envelope) throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(String.valueOf(envelope.get("eventId")));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getBody()).thenReturn(
                ByteBuffer.wrap(objectMapper.writeValueAsBytes(envelope)));
        return message;
    }

    private ChatRealtimeProperties properties() {
        return new ChatRealtimeProperties(
                true,
                null,
                "test",
                "chat-node-a",
                "127.0.0.1:18082",
                "ecommerce-chat-events-test",
                "ecommerce-chat-delivery-events-test",
                "chat-dispatcher-test",
                "chat-delivery-test",
                0,
                500,
                Duration.ofSeconds(1),
                Duration.ofSeconds(15),
                20,
                Duration.ofSeconds(12),
                Duration.ofSeconds(4),
                100);
    }
}
