package com.ecommerce.catalog.infrastructure.messaging;

import com.ecommerce.catalog.application.model.ReviewModels.OrderCompletedEvent;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.catalog.infrastructure.config.ReviewEventConsumerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCompletedConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsMalformedEventBeforeAcknowledgingPoisonMessage() throws Exception {
        ProductReviewService service = mock(ProductReviewService.class);
        CatalogConsumerFailureRecorder failures =
                mock(CatalogConsumerFailureRecorder.class);
        OrderCompletedConsumer consumer = consumer(service, failures);
        MessageView message = message(Map.of(
                "eventId", "invalid-event",
                "eventType", "OrderCompleted",
                "payloadVersion", 99));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("catalog-review-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsEventWhenProducerIdentityDoesNotMatchTradeContract() throws Exception {
        ProductReviewService service = mock(ProductReviewService.class);
        CatalogConsumerFailureRecorder failures =
                mock(CatalogConsumerFailureRecorder.class);
        OrderCompletedConsumer consumer = consumer(service, failures);
        MessageView message = orderCompletedMessage(
                "wrong-producer-event",
                "catalog-service",
                "ORDER-REVIEW-1");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("catalog-review-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsEventWhenAggregateIdentityDoesNotMatchOrderNumber() throws Exception {
        ProductReviewService service = mock(ProductReviewService.class);
        CatalogConsumerFailureRecorder failures =
                mock(CatalogConsumerFailureRecorder.class);
        OrderCompletedConsumer consumer = consumer(service, failures);
        MessageView message = orderCompletedMessage(
                "wrong-aggregate-event",
                "trade-service",
                "ORDER-OTHER");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("catalog-review-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void acknowledgesAfterTransientFailureIsDurablyOwnedByMysql()
            throws Exception {
        ProductReviewService service = mock(ProductReviewService.class);
        CatalogConsumerFailureRecorder failures =
                mock(CatalogConsumerFailureRecorder.class);
        OrderCompletedConsumer consumer = consumer(service, failures);
        MessageView message = validMessage("retry-event");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(service)
                .acceptOrderCompleted(
                        any(OrderCompletedEvent.class),
                        eq("catalog-review-consumer-test"));
        when(failures.record(
                eq(message),
                eq("catalog-review-consumer-test"),
                any(IllegalStateException.class)))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(failures).record(
                eq(message),
                eq("catalog-review-consumer-test"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    @Test
    void successfulRedeliveryMarksFailureRecoveredBeforeAcknowledgement()
            throws Exception {
        ProductReviewService service = mock(ProductReviewService.class);
        CatalogConsumerFailureRecorder failures =
                mock(CatalogConsumerFailureRecorder.class);
        OrderCompletedConsumer consumer = consumer(service, failures);
        MessageView message = validMessage("success-event");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).markRecovered(
                message,
                "catalog-review-consumer-test");
        verify(active).ack(message);
    }

    private OrderCompletedConsumer consumer(
            ProductReviewService service,
            CatalogConsumerFailureRecorder failures) {
        return new OrderCompletedConsumer(
                new ReviewEventConsumerProperties(
                        true,
                        "127.0.0.1:18082",
                        "catalog-review-consumer-test",
                        "order-events-test",
                        0,
                        500,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(15),
                        20),
                service,
                failures,
                objectMapper);
    }

    private MessageView validMessage(String eventId) throws Exception {
        return orderCompletedMessage(eventId, "trade-service", "ORDER-REVIEW-1");
    }

    private MessageView orderCompletedMessage(
            String eventId,
            String producer,
            String aggregateId) throws Exception {
        return message(Map.of(
                "eventId", eventId,
                "eventType", "OrderCompleted",
                "producer", producer,
                "aggregateType", "TradeOrder",
                "aggregateId", aggregateId,
                "aggregateVersion", 1,
                "payloadVersion", 1,
                "occurredAt", Instant.parse("2026-07-24T00:00:00Z").toString(),
                "payload", Map.of(
                        "orderNo", "ORDER-REVIEW-1",
                        "userId", 1001L,
                        "items", new Object[] {
                                Map.of(
                                        "lineNo", 1,
                                        "productId", 2001L,
                                        "skuId", 3001L,
                                        "productTitle", "素简通勤包",
                                        "skuCode", "PJ-BAG-001",
                                        "skuName", "雾灰",
                                        "specJson", "{\"color\":\"雾灰\"}",
                                        "imageObjectKey", "catalog/bag-001.webp",
                                        "quantity", 1L)
                        })));
    }

    private MessageView message(Map<String, Object> envelope) throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(
                String.valueOf(envelope.get("eventId")));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getBody()).thenReturn(
                ByteBuffer.wrap(objectMapper.writeValueAsBytes(envelope)));
        return message;
    }
}
