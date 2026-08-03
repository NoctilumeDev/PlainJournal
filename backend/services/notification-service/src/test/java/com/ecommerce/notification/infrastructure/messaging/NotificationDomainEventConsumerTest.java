package com.ecommerce.notification.infrastructure.messaging;

import com.ecommerce.notification.application.model.NotificationModels.DomainEvent;
import com.ecommerce.notification.application.service.NotificationApplicationService;
import com.ecommerce.notification.infrastructure.config.NotificationEventConsumerProperties;
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

class NotificationDomainEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordsMalformedEventBeforeAcknowledgingPoisonMessage() throws Exception {
        NotificationApplicationService service = mock(NotificationApplicationService.class);
        NotificationConsumerFailureRecorder failures = mock(NotificationConsumerFailureRecorder.class);
        NotificationDomainEventConsumer consumer = consumer(service, failures);
        MessageView message = message(Map.of(
                "eventId", "invalid-event",
                "eventType", "PaymentSucceeded",
                "payloadVersion", 99));
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("notification-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsEventWhenProducerIdentityDoesNotMatchEventContract() throws Exception {
        NotificationApplicationService service = mock(NotificationApplicationService.class);
        NotificationConsumerFailureRecorder failures = mock(NotificationConsumerFailureRecorder.class);
        NotificationDomainEventConsumer consumer = consumer(service, failures);
        MessageView message = paymentMessage("wrong-producer-event", "trade-service", "PAY-1");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("notification-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsEventWhenAggregateIdentityDoesNotMatchPayload() throws Exception {
        NotificationApplicationService service = mock(NotificationApplicationService.class);
        NotificationConsumerFailureRecorder failures = mock(NotificationConsumerFailureRecorder.class);
        NotificationDomainEventConsumer consumer = consumer(service, failures);
        MessageView message = paymentMessage(
                "wrong-aggregate-event",
                "payment-service",
                "PAY-OTHER");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).recordTerminal(
                eq(message),
                eq("notification-consumer-test"),
                any(IllegalArgumentException.class));
        verify(active).ack(message);
        verifyNoInteractions(service);
    }

    @Test
    void acknowledgesAfterTransientFailureIsDurablyOwnedByMysql() throws Exception {
        NotificationApplicationService service = mock(NotificationApplicationService.class);
        NotificationConsumerFailureRecorder failures = mock(NotificationConsumerFailureRecorder.class);
        NotificationDomainEventConsumer consumer = consumer(service, failures);
        MessageView message = validMessage("retry-event");
        SimpleConsumer active = mock(SimpleConsumer.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(service)
                .acceptDomainEvent(any(DomainEvent.class), eq("notification-consumer-test"));
        when(failures.record(
                eq(message),
                eq("notification-consumer-test"),
                any(IllegalStateException.class)))
                .thenReturn(false);

        consumer.processMessage(message, active);

        verify(failures).record(
                eq(message),
                eq("notification-consumer-test"),
                any(IllegalStateException.class));
        verify(active).ack(message);
    }

    @Test
    void successfulRedeliveryMarksFailureRecoveredBeforeAcknowledgement() throws Exception {
        NotificationApplicationService service = mock(NotificationApplicationService.class);
        NotificationConsumerFailureRecorder failures = mock(NotificationConsumerFailureRecorder.class);
        NotificationDomainEventConsumer consumer = consumer(service, failures);
        MessageView message = validMessage("success-event");
        SimpleConsumer active = mock(SimpleConsumer.class);

        consumer.processMessage(message, active);

        verify(failures).markRecovered(message, "notification-consumer-test");
        verify(active).ack(message);
    }

    private NotificationDomainEventConsumer consumer(
            NotificationApplicationService service,
            NotificationConsumerFailureRecorder failures) {
        return new NotificationDomainEventConsumer(
                new NotificationEventConsumerProperties(
                        true,
                        "127.0.0.1:18082",
                        "notification-consumer-test",
                        "payment-topic-test",
                        "logistics-topic-test",
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
        return paymentMessage(eventId, "payment-service", "PAY-1");
    }

    private MessageView paymentMessage(
            String eventId,
            String producer,
            String aggregateId) throws Exception {
        return message(Map.of(
                "eventId", eventId,
                "eventType", "PaymentSucceeded",
                "producer", producer,
                "aggregateType", "PaymentOrder",
                "aggregateId", aggregateId,
                "aggregateVersion", 1,
                "payloadVersion", 1,
                "payload", Map.of(
                        "userId", 1001L,
                        "paymentNo", "PAY-1",
                        "orderNo", "ORDER-1")));
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
}
