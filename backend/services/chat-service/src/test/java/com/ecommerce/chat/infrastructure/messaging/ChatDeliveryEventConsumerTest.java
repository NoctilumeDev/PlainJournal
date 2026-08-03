package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeDeliveryService;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatDeliveryEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsMalformedPayloadBeforeAcknowledgingPoisonMessage() throws Exception {
        ChatRealtimeDeliveryService deliveryService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer consumer = consumer(deliveryService, failureRecorder);
        MessageView message = message(Map.of(
                "eventId", "delivery-invalid",
                "eventType", "ChatDeliveryRequested",
                "payloadVersion", 99));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("chat-delivery-test-chat-node-a"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(deliveryService);
    }

    @Test
    void recordsWrongNodeAsTerminalRoutingFailure() throws Exception {
        ChatRealtimeDeliveryService deliveryService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer consumer = consumer(deliveryService, failureRecorder);
        MessageView message = validMessage("delivery-wrong-node", "chat-node-b");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("chat-delivery-test-chat-node-a"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(deliveryService);
    }

    @Test
    void acknowledgesAfterDurableRetryOwnershipIsEstablished() throws Exception {
        ChatRealtimeDeliveryService deliveryService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer consumer = consumer(deliveryService, failureRecorder);
        MessageView message = validMessage("delivery-retry", "chat-node-a");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(deliveryService)
                .deliver(3001L, 3002L);
        when(failureRecorder.record(
                eq(message),
                eq("chat-delivery-test-chat-node-a"),
                any(IllegalStateException.class)))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(active).ack(message);
    }

    @Test
    void acknowledgesAfterRetryBudgetIsExhausted() throws Exception {
        ChatRealtimeDeliveryService deliveryService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer consumer = consumer(deliveryService, failureRecorder);
        MessageView message = validMessage("delivery-terminal", "chat-node-a");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(deliveryService)
                .deliver(3001L, 3002L);
        when(failureRecorder.record(
                eq(message),
                eq("chat-delivery-test-chat-node-a"),
                any(IllegalStateException.class)))
                .thenReturn(true);

        consumer.processMessage(message, active);

        verify(active).ack(message);
    }

    @Test
    void successfulOrOfflineRedeliveryMarksFailureRecovered() throws Exception {
        ChatRealtimeDeliveryService deliveredService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder deliveredRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer deliveredConsumer = consumer(
                deliveredService,
                deliveredRecorder);
        MessageView deliveredMessage = validMessage("delivery-recovered", "chat-node-a");
        SimpleConsumer deliveredActive = mock(SimpleConsumer.class);

        deliveredConsumer.processMessage(deliveredMessage, deliveredActive);

        verify(deliveredRecorder).markRecovered(
                deliveredMessage,
                "chat-delivery-test-chat-node-a");
        verify(deliveredActive).ack(deliveredMessage);

        ChatRealtimeDeliveryService offlineService = mock(ChatRealtimeDeliveryService.class);
        ConsumerFailureRecorder offlineRecorder = mock(ConsumerFailureRecorder.class);
        ChatDeliveryEventConsumer offlineConsumer = consumer(offlineService, offlineRecorder);
        MessageView offlineMessage = validMessage("delivery-offline", "chat-node-a");
        SimpleConsumer offlineActive = mock(SimpleConsumer.class);
        doThrow(new IOException("socket closed"))
                .when(offlineService)
                .deliver(3001L, 3002L);

        offlineConsumer.processMessage(offlineMessage, offlineActive);

        verify(offlineRecorder).markRecovered(
                offlineMessage,
                "chat-delivery-test-chat-node-a");
        verify(offlineActive).ack(offlineMessage);
    }

    private ChatDeliveryEventConsumer consumer(
            ChatRealtimeDeliveryService deliveryService,
            ConsumerFailureRecorder failureRecorder) {
        return new ChatDeliveryEventConsumer(
                properties(),
                deliveryService,
                objectMapper,
                failureRecorder);
    }

    private MessageView validMessage(String eventId, String targetNodeId) throws Exception {
        return message(Map.of(
                "eventId", eventId,
                "eventType", "ChatDeliveryRequested",
                "payloadVersion", 1,
                "payload", Map.of(
                        "messageId", 3001L,
                        "recipientId", 3002L,
                        "targetNodeId", targetNodeId)));
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
