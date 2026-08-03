package com.ecommerce.fulfillment.infrastructure.messaging;

import com.ecommerce.fulfillment.application.service.OrderPaidHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
                "payload", Map.of(
                        "orderNo", "ORD-INVALID",
                        "userId", 1L,
                        "deliveryAddress", Map.of(
                                "recipientName", "顾客",
                                "phone", "13800000000",
                                "province", "上海市",
                                "city", "上海市",
                                "district", "浦东新区",
                                "detailAddress", "测试路 1 号"))));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("fulfillment-order-paid-v1"),
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
                        "userId", 1L,
                        "deliveryAddress", Map.of(
                                "sourceAddressId", 2L,
                                "recipientName", "顾客",
                                "phone", "13800000000",
                                "province", "上海市",
                                "city", "上海市",
                                "district", "浦东新区",
                                "detailAddress", "测试路 1 号"))));
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable")).when(handler).handle(any());
        when(failureRecorder.record(eq(message), eq("fulfillment-order-paid-v1"), any()))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(failureRecorder).record(
                eq(message),
                eq("fulfillment-order-paid-v1"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    @Test
    void acceptsPreviouslyPublishedStringAddressIdAndAcknowledgesIt() throws Exception {
        OrderPaidHandler handler = mock(OrderPaidHandler.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        OrderPaidConsumer consumer = consumer(handler, failureRecorder);
        MessageView message = message(Map.of(
                "eventId", "event-string-address",
                "eventType", "OrderPaid",
                "payloadVersion", 1,
                "payload", Map.of(
                        "orderNo", "ORD-STRING-ADDRESS",
                        "userId", 1L,
                        "deliveryAddress", Map.of(
                                "sourceAddressId", "9223372036854775807",
                                "recipientName", "顾客",
                                "phone", "13800000000",
                                "province", "上海市",
                                "city", "上海市",
                                "district", "浦东新区",
                                "detailAddress", "测试路 1 号"))));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(handler).handle(argThat(command ->
                command.deliveryAddress().sourceAddressId().equals(Long.MAX_VALUE)));
        verify(failureRecorder).markRecovered(message, "fulfillment-order-paid-v1");
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
                        "fulfillment-order-paid-v1",
                        1000,
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
