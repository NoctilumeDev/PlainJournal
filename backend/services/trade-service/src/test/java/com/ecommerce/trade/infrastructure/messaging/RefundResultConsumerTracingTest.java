package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.trade.application.service.AfterSaleService;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
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
class RefundResultConsumerTracingTest {

    private final ObjectMapper objectMapper;
    private final MessagingTracing messagingTracing;
    private final Tracer tracer;

    @Autowired
    RefundResultConsumerTracingTest(
            ObjectMapper objectMapper,
            MessagingTracing messagingTracing,
            Tracer tracer) {
        this.objectMapper = objectMapper;
        this.messagingTracing = messagingTracing;
        this.tracer = tracer;
    }

    @Test
    void restoresRocketMqParentContextAndAcknowledgesInsideRefundConsumerSpan() throws Exception {
        Map<String, String> producerContext = producerContext();
        AfterSaleService afterSaleService = mock(AfterSaleService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        AtomicReference<Map<String, String>> consumerContext = new AtomicReference<>();
        doAnswer(invocation -> {
            consumerContext.set(messagingTracing.capture());
            return null;
        }).when(afterSaleService).applyRefundEvent(any());
        RefundResultConsumer consumer = consumer(afterSaleService, failureRecorder);
        MessageView message = message(producerContext);
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        assertThat(producerContext).containsKey("traceparent");
        assertThat(consumerContext.get()).containsKey("traceparent");
        assertThat(traceId(consumerContext.get().get("traceparent")))
                .isEqualTo(traceId(producerContext.get("traceparent")));
        assertThat(spanId(consumerContext.get().get("traceparent")))
                .isNotEqualTo(spanId(producerContext.get("traceparent")));
        verify(failureRecorder).markRecovered(message, "trade-refund-events-v1");
        verify(active).ack(message);
    }

    @Test
    void acknowledgesRefundMessageAfterDurableRetryRecordOwnsRecovery() throws Exception {
        AfterSaleService afterSaleService = mock(AfterSaleService.class);
        ConsumerFailureRecorder failureRecorder = mock(ConsumerFailureRecorder.class);
        doThrow(new IllegalStateException("trade database unavailable"))
                .when(afterSaleService).applyRefundEvent(any());
        RefundResultConsumer consumer = consumer(afterSaleService, failureRecorder);
        MessageView message = message(producerContext());
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failureRecorder).record(
                eq(message),
                eq("trade-refund-events-v1"),
                any(IllegalStateException.class));
        verify(failureRecorder, never()).markRecovered(any(), any());
        verify(active).ack(message);
    }

    private RefundResultConsumer consumer(
            AfterSaleService afterSaleService,
            ConsumerFailureRecorder failureRecorder) {
        RefundResultConsumerProperties properties = new RefundResultConsumerProperties(
                true, "127.0.0.1:18082", "ecommerce-payment-events",
                "trade-refund-events-v1", 1000, 16,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        return new RefundResultConsumer(
                properties, afterSaleService, mock(TradeOrderService.class),
                objectMapper, failureRecorder, messagingTracing);
    }

    private MessageView message(Map<String, String> traceContext) throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn("refund-message-tracing-001");
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getProperties()).thenReturn(traceContext);
        when(message.getBody()).thenReturn(ByteBuffer.wrap(objectMapper.writeValueAsBytes(Map.of(
                "eventId", "00000000-0000-0000-0000-000000000902",
                "eventType", "RefundSucceeded",
                "payloadVersion", 1,
                "payload", Map.of(
                        "refundNo", "RF-902",
                        "afterSaleNo", "AS-902",
                        "orderNo", "ORD-902",
                        "paymentNo", "PAY-902",
                        "userId", 1L,
                        "amount", new BigDecimal("39.80"))))));
        return message;
    }

    private Map<String, String> producerContext() {
        Span producer = tracer.nextSpan().name("rocketmq refund test producer").start();
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
