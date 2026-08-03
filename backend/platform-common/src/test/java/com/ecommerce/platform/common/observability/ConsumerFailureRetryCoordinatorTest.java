package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerFailureRetryCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-25T04:00:00Z");

    @Test
    void claimsAndRecoversWithTheMatchingHandler() {
        FakeStore store = new FakeStore(retry("message-1", "group-1", 1));
        List<String> payloads = new ArrayList<>();
        ConsumerFailureRetryCoordinator coordinator = coordinator(
                store,
                handler("group-1", payloads::add),
                16);

        coordinator.retryDueFailures();

        assertThat(payloads).containsExactly("{\"eventId\":\"event-1\"}");
        assertThat(store.claimed).isTrue();
        assertThat(store.claimedAttempts).isEqualTo(1);
        assertThat(store.recovered).isTrue();
        assertThat(store.failedStatus).isNull();
        assertThat(store.currentTimeCalls).isEqualTo(3);
    }

    @Test
    void reschedulesTransientFailureAndAdvancesTheAttemptBudget() {
        FakeStore store = new FakeStore(retry("message-2", "group-2", 3));
        ConsumerFailureRetryCoordinator coordinator = coordinator(
                store,
                handler("group-2", ignored -> {
                    throw new IllegalStateException("database unavailable");
                }),
                16);

        coordinator.retryDueFailures();

        assertThat(store.failedAttempts).isEqualTo(4);
        assertThat(store.failedStatus).isEqualTo("RETRYING");
        assertThat(store.nextAttemptAt).isEqualTo(NOW.plusSeconds(15));
        assertThat(store.recovered).isFalse();
    }

    @Test
    void movesInvalidPayloadDirectlyToNeedsAttention() {
        FakeStore store = new FakeStore(retry("message-3", "group-3", 1));
        ConsumerFailureRetryCoordinator coordinator = coordinator(
                store,
                handler("group-3", ignored -> {
                    throw new IllegalArgumentException("invalid payload");
                }),
                16);

        coordinator.retryDueFailures();

        assertThat(store.failedAttempts).isEqualTo(2);
        assertThat(store.failedStatus).isEqualTo("NEEDS_ATTENTION");
        assertThat(store.nextAttemptAt).isNull();
    }

    @Test
    void keepsRetryingRecordUntouchedWhenItsHandlerIsDisabled() {
        FakeStore store = new FakeStore(retry("message-4", "disabled-group", 1));
        ConsumerFailureRetryCoordinator coordinator = coordinator(store, null, 16);

        coordinator.retryDueFailures();

        assertThat(store.claimed).isFalse();
        assertThat(store.recovered).isFalse();
        assertThat(store.failedStatus).isNull();
    }

    private ConsumerFailureRetryCoordinator coordinator(
            FakeStore store,
            ConsumerFailureRetryHandler handler,
            int maximumAttempts) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ConsumerFailureObservability observability = new ConsumerFailureObservability(
                new SimpleMeterRegistry(),
                "test-service",
                store,
                clock);
        return new ConsumerFailureRetryCoordinator(
                "test-service",
                store,
                observability,
                maximumAttempts,
                20,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                "worker-1",
                handler == null ? List.of() : List.of(handler));
    }

    private ConsumerFailureRetryHandler handler(
            String consumerGroup,
            CheckedPayloadConsumer consumer) {
        return new ConsumerFailureRetryHandler() {
            @Override
            public String consumerGroup() {
                return consumerGroup;
            }

            @Override
            public void retry(String rawPayload) throws Exception {
                consumer.accept(rawPayload);
            }
        };
    }

    private ConsumerFailureRetryEntry retry(
            String messageId,
            String consumerGroup,
            int attempts) {
        ConsumerFailureRetryEntry entry = new ConsumerFailureRetryEntry();
        entry.setMessageId(messageId);
        entry.setConsumerGroup(consumerGroup);
        entry.setRawPayload("{\"eventId\":\"event-1\"}");
        entry.setAttempts(attempts);
        return entry;
    }

    @FunctionalInterface
    private interface CheckedPayloadConsumer {
        void accept(String payload) throws Exception;
    }

    private static final class FakeStore implements ConsumerFailureRetryStore {

        private final ConsumerFailureRetryEntry entry;
        private boolean claimed;
        private boolean recovered;
        private int claimedAttempts;
        private int failedAttempts;
        private String failedStatus;
        private Instant nextAttemptAt;
        private int currentTimeCalls;

        private FakeStore(ConsumerFailureRetryEntry entry) {
            this.entry = entry;
        }

        @Override
        public Instant currentTime() {
            currentTimeCalls++;
            return NOW;
        }

        @Override
        public List<ConsumerFailureRetryEntry> selectRetryable(Instant now, int limit) {
            return List.of(entry);
        }

        @Override
        public int claimRetry(
                String messageId,
                String consumerGroup,
                String owner,
                int expectedAttempts,
                Instant now,
                Instant claimUntil) {
            claimed = true;
            claimedAttempts = expectedAttempts;
            return 1;
        }

        @Override
        public int markRetryRecovered(
                String messageId,
                String consumerGroup,
                String owner,
                Instant now) {
            recovered = true;
            return 1;
        }

        @Override
        public int markRetryFailed(
                String messageId,
                String consumerGroup,
                String owner,
                int attempts,
                String status,
                String error,
                Instant nextAttemptAt,
                Instant now) {
            failedAttempts = attempts;
            failedStatus = status;
            this.nextAttemptAt = nextAttemptAt;
            return 1;
        }

        @Override
        public long countByStatus(String status) {
            return 0;
        }

        @Override
        public Instant selectOldestActiveFailedAt() {
            return null;
        }

        @Override
        public List<ConsumerFailureEntry> selectRecentActive(int limit) {
            return List.of();
        }
    }
}
