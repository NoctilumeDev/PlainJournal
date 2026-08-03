package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@AutoConfigureObservability
@SpringBootTest
class PaymentSucceededConsumerTracingTest {

    private final ObjectMapper objectMapper;
    private final MessagingTracing messagingTracing;
    private final Tracer tracer;

    @Autowired
    PaymentSucceededConsumerTracingTest(
            ObjectMapper objectMapper,
            MessagingTracing messagingTracing,
            Tracer tracer) {
        this.objectMapper = objectMapper;
        this.messagingTracing = messagingTracing;
        this.tracer = tracer;
    }

    @Test
    void restoresRocketMqParentContextAndAcknowledgesInsideConsumerSpan() throws Exception {
        Map<String, String> producerContext = producerContext();
        TradeOrderService orderService = mock(TradeOrderService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        AtomicReference<Map<String, String>> consumerContext = new AtomicReference<>();
        doAnswer(invocation -> {
            consumerContext.set(messagingTracing.capture());
            return null;
        }).when(orderService).applyPaymentSucceeded(any());
        PaymentSucceededConsumer consumer = consumer(orderService, failureRecorder);
        MessageView message = message(producerContext);
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        assertThat(producerContext).containsKey("traceparent");
        assertThat(consumerContext.get()).containsKey("traceparent");
        assertThat(traceId(consumerContext.get().get("traceparent")))
                .isEqualTo(traceId(producerContext.get("traceparent")));
        assertThat(spanId(consumerContext.get().get("traceparent")))
                .isNotEqualTo(spanId(producerContext.get("traceparent")));
        verify(failureRecorder).markRecovered(message, "trade-payment-succeeded-v1");
        verify(active).ack(message);
    }

    @Test
    void acknowledgesMessageAfterDurableRetryRecordOwnsRecovery() throws Exception {
        TradeOrderService orderService = mock(TradeOrderService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(orderService).applyPaymentSucceeded(any());
        PaymentSucceededConsumer consumer = consumer(orderService, failureRecorder);
        MessageView message = message(producerContext());
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).record(
                eq(message),
                eq("trade-payment-succeeded-v1"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    @Test
    void acknowledgesAnUnsupportedPayloadVersionWithoutRunningBusinessLogic() throws Exception {
        TradeOrderService orderService = mock(TradeOrderService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        PaymentSucceededConsumer consumer = consumer(orderService, failureRecorder);
        MessageView message = message(producerContext(), 2);
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).recordTerminal(
                eq(message),
                eq("trade-payment-succeeded-v1"),
                any(IllegalArgumentException.class));
        verify(orderService, never()).applyPaymentSucceeded(any());
        verify(active).ack(message);
    }

    private PaymentSucceededConsumer consumer(
            TradeOrderService orderService,
            ConsumerFailureRecorder failureRecorder) {
        PaymentEventConsumerProperties properties = new PaymentEventConsumerProperties(
                true, "127.0.0.1:18082", "ecommerce-payment-events",
                "trade-payment-succeeded-v1", 16, Duration.ofSeconds(30), Duration.ofSeconds(5));
        return new PaymentSucceededConsumer(
                properties,
                orderService,
                objectMapper,
                failureRecorder,
                messagingTracing,
                new ProcessTerminationFaultInjector(ProcessTerminationFaultProperties.disabled()),
                new SimpleMeterRegistry());
    }

    private MessageView message(Map<String, String> traceContext) throws Exception {
        return message(traceContext, 1);
    }

    private MessageView message(Map<String, String> traceContext, int payloadVersion) throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("message-tracing-001");
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getProperties()).thenReturn(traceContext);
        when(message.getBody()).thenReturn(ByteBuffer.wrap(objectMapper.writeValueAsBytes(Map.of(
                "eventId", "00000000-0000-0000-0000-000000000901",
                "eventType", "PaymentSucceeded",
                "payloadVersion", payloadVersion,
                "payload", Map.of(
                        "paymentNo", "PAY-901",
                        "orderNo", "ORD-901",
                        "userId", 1L,
                        "reservationNo", "RSV-901",
                        "amount", new BigDecimal("39.80"))))));
        return message;
    }

    private Map<String, String> producerContext() {
        Span producer = tracer.nextSpan().name("rocketmq test producer").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(producer)) {
            return messagingTracing.capture();
        } finally {
            producer.end();
        }
    }

    private String traceId(String traceparent) {
        return traceparent.split("-")[1];
    }

    private String spanId(String traceparent) {
        return traceparent.split("-")[2];
    }
}
