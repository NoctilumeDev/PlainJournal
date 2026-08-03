package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.entity.ConsumerFailureRetryEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatConsumerFailureRetryJobTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
    private static final String DISPATCHER_GROUP = "chat-dispatcher-test";
    private static final String DELIVERY_GROUP = "chat-delivery-test-chat-node-a";
    private static final String WORKER_ID = "chat-retry-worker-a";

    @Test
    void retriesDispatcherFailureAndMarksItRecovered() {
        Fixture fixture = fixture(retry("stored-1", DISPATCHER_GROUP));

        fixture.job().retryDueFailures();

        verify(fixture.storedConsumer()).retryPayload("{\"eventType\":\"ChatMessageStored\"}");
        verify(fixture.recorder()).markRetryRecovered(fixture.retry(), WORKER_ID);
        verifyNoInteractions(fixture.deliveryConsumer());
    }

    @Test
    void retriesDeliveryFailureAndMarksItRecovered() throws Exception {
        Fixture fixture = fixture(retry("delivery-1", DELIVERY_GROUP));

        fixture.job().retryDueFailures();

        verify(fixture.deliveryConsumer())
                .retryPayload("{\"eventType\":\"ChatMessageStored\"}");
        verify(fixture.recorder()).markRetryRecovered(fixture.retry(), WORKER_ID);
        verifyNoInteractions(fixture.storedConsumer());
    }

    @Test
    void treatsOfflineDeliveryAsRecoveredForDurableReplay() throws Exception {
        Fixture fixture = fixture(retry("delivery-offline", DELIVERY_GROUP));
        doThrow(new IOException("socket closed"))
                .when(fixture.deliveryConsumer())
                .retryPayload(fixture.retry().getRawPayload());

        fixture.job().retryDueFailures();

        verify(fixture.recorder()).markRetryRecovered(fixture.retry(), WORKER_ID);
    }

    @Test
    void reschedulesTransientDispatcherFailure() {
        Fixture fixture = fixture(retry("stored-retry", DISPATCHER_GROUP));
        IllegalStateException failure = new IllegalStateException("Redis unavailable");
        doThrow(failure)
                .when(fixture.storedConsumer())
                .retryPayload(fixture.retry().getRawPayload());
        when(fixture.recorder().recordRetryFailure(
                fixture.retry(),
                WORKER_ID,
                failure,
                false))
                .thenReturn(new ConsumerFailureRecorder.RetryFailureResult(true, false, 2));

        fixture.job().retryDueFailures();

        verify(fixture.recorder()).recordRetryFailure(
                fixture.retry(),
                WORKER_ID,
                failure,
                false);
    }

    @Test
    void sendsInvalidRetryPayloadDirectlyToNeedsAttention() {
        Fixture fixture = fixture(retry("stored-invalid", DISPATCHER_GROUP));
        IllegalArgumentException failure =
                new IllegalArgumentException("Unsupported event contract");
        doThrow(failure)
                .when(fixture.storedConsumer())
                .retryPayload(fixture.retry().getRawPayload());
        when(fixture.recorder().recordRetryFailure(
                fixture.retry(),
                WORKER_ID,
                failure,
                true))
                .thenReturn(new ConsumerFailureRecorder.RetryFailureResult(true, true, 2));

        fixture.job().retryDueFailures();

        verify(fixture.recorder()).recordRetryFailure(
                fixture.retry(),
                WORKER_ID,
                failure,
                true);
    }

    @Test
    void skipsCandidateWhenAnotherWorkerWinsTheClaim() {
        ConsumerFailureRetryEntity retry = retry("stored-contended", DISPATCHER_GROUP);
        Fixture fixture = fixture(retry, 0);

        fixture.job().retryDueFailures();

        verifyNoInteractions(
                fixture.storedConsumer(),
                fixture.deliveryConsumer(),
                fixture.recorder());
    }

    private Fixture fixture(ConsumerFailureRetryEntity retry) {
        return fixture(retry, 1);
    }

    private Fixture fixture(ConsumerFailureRetryEntity retry, int claimedRows) {
        ConsumerFailureMapper mapper = mock(ConsumerFailureMapper.class);
        ConsumerFailureRecorder recorder = mock(ConsumerFailureRecorder.class);
        ChatStoredEventConsumer storedConsumer = mock(ChatStoredEventConsumer.class);
        ChatDeliveryEventConsumer deliveryConsumer = mock(ChatDeliveryEventConsumer.class);
        ChatRealtimeProperties realtimeProperties = realtimeProperties();
        ChatConsumerFailureRetryProperties retryProperties =
                new ChatConsumerFailureRetryProperties(
                        0,
                        250,
                        Duration.ofSeconds(10),
                        20,
                        WORKER_ID,
                        Duration.ofSeconds(15));
        when(mapper.selectRetryable(
                DISPATCHER_GROUP,
                DELIVERY_GROUP,
                NOW,
                20))
                .thenReturn(List.of(retry));
        when(mapper.currentTime()).thenReturn(NOW);
        when(mapper.claimRetry(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                WORKER_ID,
                retry.getAttempts(),
                NOW,
                NOW.plusSeconds(15)))
                .thenReturn(claimedRows);
        when(recorder.markRetryRecovered(retry, WORKER_ID)).thenReturn(true);
        ChatConsumerFailureRetryJob job = new ChatConsumerFailureRetryJob(
                mapper,
                recorder,
                storedConsumer,
                deliveryConsumer,
                realtimeProperties,
                retryProperties);
        return new Fixture(
                job,
                retry,
                recorder,
                storedConsumer,
                deliveryConsumer);
    }

    private ConsumerFailureRetryEntity retry(String messageId, String consumerGroup) {
        ConsumerFailureRetryEntity retry = new ConsumerFailureRetryEntity();
        retry.setMessageId(messageId);
        retry.setConsumerGroup(consumerGroup);
        retry.setRawPayload("{\"eventType\":\"ChatMessageStored\"}");
        retry.setAttempts(1);
        retry.setNextAttemptAt(NOW);
        return retry;
    }

    private ChatRealtimeProperties realtimeProperties() {
        return new ChatRealtimeProperties(
                true,
                null,
                "test",
                "chat-node-a",
                "127.0.0.1:18082",
                "ecommerce-chat-events-test",
                "ecommerce-chat-delivery-events-test",
                DISPATCHER_GROUP,
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

    private record Fixture(
            ChatConsumerFailureRetryJob job,
            ConsumerFailureRetryEntity retry,
            ConsumerFailureRecorder recorder,
            ChatStoredEventConsumer storedConsumer,
            ChatDeliveryEventConsumer deliveryConsumer) {
    }
}
