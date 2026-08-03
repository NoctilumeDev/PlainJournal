package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.application.service.OrderPaidHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

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

class OrderPaidConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsMalformedPayloadAsNeedsAttentionAndAcknowledgesIt() throws Exception {
        OrderPaidHandler handler = mock(OrderPaidHandler.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        OrderPaidConsumer consumer = consumer(handler, failureRecorder);
        MessageView message = message(Map.of(
                "eventId", "event-invalid",
                "eventType", "OrderPaid",
                "payload", Map.of("orderNo", "ORD-INVALID")));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("inventory-order-paid-v1"),
                any(Exception.class));
        verify(active).ack(message);
        verifyNoInteractions(handler);
    }

    @Test
    void acknowledgesMessageAfterDurableRetryRecordOwnsRecovery() throws Exception {
        OrderPaidHandler handler = mock(OrderPaidHandler.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        OrderPaidConsumer consumer = consumer(handler, failureRecorder);
        MessageView message = message(Map.of(
                "eventId", "event-retry",
                "eventType", "OrderPaid",
                "payloadVersion", 1,
                "payload", Map.of(
                        "orderNo", "ORD-RETRY",
                        "reservationNo", "RSV-RETRY")));
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable")).when(handler).handle(any());
        when(failureRecorder.record(eq(message), eq("inventory-order-paid-v1"), any()))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(failureRecorder).record(
                eq(message),
                eq("inventory-order-paid-v1"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    private OrderPaidConsumer consumer(
            OrderPaidHandler handler,
            ConsumerFailureRecorder failureRecorder) {
        return new OrderPaidConsumer(
                new OrderEventConsumerProperties(
                        true,
                        "127.0.0.1:18082",
                        "ecommerce-order-events",
                        "inventory-order-paid-v1",
                        16,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5)),
                handler,
                objectMapper,
                failureRecorder);
    }

    private MessageView message(Map<String, Object> envelope) throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(String.valueOf(envelope.get("eventId")));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getBody()).thenReturn(ByteBuffer.wrap(objectMapper.writeValueAsBytes(envelope)));
        return message;
    }
}
