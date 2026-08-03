package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class ConsumerFailureRetryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T04:30:00Z");
    private static final String GROUP = "inventory-retry-integration";

    private final ConsumerFailureMapper mapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ConsumerFailureRetryIntegrationTest(
            ConsumerFailureMapper mapper,
            JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update(
                "DELETE FROM consumer_failure WHERE consumer_group = ?",
                GROUP);
    }

    @Test
    void claimsOnceAndFencesAnExpiredOwnerBeforeRecovery() {
        insertRetry("lease-message", 1, NOW.minusSeconds(1));

        assertThat(mapper.claimRetry(
                "lease-message", GROUP, "worker-a", 1, NOW, NOW.plusSeconds(10)))
                .isEqualTo(1);
        assertThat(mapper.claimRetry(
                "lease-message", GROUP, "worker-b", 1,
                NOW.plusSeconds(1), NOW.plusSeconds(11)))
                .isZero();

        Instant reclaimedAt = NOW.plusSeconds(11);
        assertThat(mapper.claimRetry(
                "lease-message",
                GROUP,
                "worker-b",
                1,
                reclaimedAt,
                reclaimedAt.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(mapper.markRetryRecovered(
                "lease-message", GROUP, "worker-a", reclaimedAt.plusSeconds(1)))
                .isZero();
        assertThat(mapper.markRetryRecovered(
                "lease-message", GROUP, "worker-b", reclaimedAt.plusSeconds(1)))
                .isEqualTo(1);

        assertThat(state("lease-message"))
                .containsEntry("status", "RECOVERED")
                .containsEntry("claim_owner", null)
                .containsEntry("claim_until", null)
                .containsEntry("next_attempt_at", null);
    }

    @Test
    void staleAttemptsSnapshotCannotClaimARescheduledFailure() {
        insertRetry("stale-attempts-message", 1, NOW.minusSeconds(1));
        assertThat(mapper.claimRetry(
                "stale-attempts-message",
                GROUP,
                "worker-a",
                1,
                NOW,
                NOW.plusSeconds(30))).isEqualTo(1);
        assertThat(mapper.markRetryFailed(
                "stale-attempts-message",
                GROUP,
                "worker-a",
                2,
                "RETRYING",
                "fault injected",
                NOW.plusSeconds(15),
                NOW.plusSeconds(1))).isEqualTo(1);

        assertThat(mapper.claimRetry(
                "stale-attempts-message",
                GROUP,
                "worker-b",
                1,
                NOW.plusSeconds(16),
                NOW.plusSeconds(46))).isZero();
        assertThat(mapper.claimRetry(
                "stale-attempts-message",
                GROUP,
                "worker-b",
                2,
                NOW.plusSeconds(16),
                NOW.plusSeconds(46))).isEqualTo(1);
    }

    @Test
    void keepsBrokerFailureUpdatesMonotonicAndDoesNotRegressTerminalStates() {
        Instant earlierRetry = NOW.minusSeconds(5);
        insertRetry("monotonic-message", 3, earlierRetry);

        assertThat(mapper.markFailed(
                "monotonic-message",
                GROUP,
                1,
                "RETRYING",
                "late first delivery",
                NOW,
                NOW.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(state("monotonic-message"))
                .containsEntry("attempts", 3)
                .containsEntry("next_attempt_at", Timestamp.from(earlierRetry));

        assertThat(mapper.markRecovered("monotonic-message", GROUP, NOW.plusSeconds(1)))
                .isEqualTo(1);
        assertThat(mapper.markFailed(
                "monotonic-message",
                GROUP,
                4,
                "RETRYING",
                "late duplicate after recovery",
                NOW.plusSeconds(2),
                NOW.plusSeconds(30)))
                .isZero();
        assertThat(state("monotonic-message"))
                .containsEntry("status", "RECOVERED")
                .containsEntry("attempts", 3)
                .containsEntry("next_attempt_at", null);

        insertFailure(
                "attention-message",
                16,
                "NEEDS_ATTENTION",
                null);
        assertThat(mapper.markFailed(
                "attention-message",
                GROUP,
                1,
                "RETRYING",
                "late duplicate after terminal decision",
                NOW.plusSeconds(2),
                NOW.plusSeconds(30)))
                .isZero();
        assertThat(state("attention-message"))
                .containsEntry("status", "NEEDS_ATTENTION")
                .containsEntry("attempts", 16)
                .containsEntry("next_attempt_at", null);
    }

    @Test
    void coordinatorRecoversSuccessAndMovesExhaustedFailureToNeedsAttention() {
        insertRetry("success-message", 1, NOW.minusSeconds(1));
        coordinator(handler(payload -> {
            assertThat(payload).contains("success-message");
        }), 3).retryDueFailures();
        assertThat(state("success-message"))
                .containsEntry("status", "RECOVERED")
                .containsEntry("next_attempt_at", null);

        insertRetry("exhausted-message", 2, NOW.minusSeconds(1));
        coordinator(handler(payload -> {
            throw new IllegalStateException("database still unavailable");
        }), 3).retryDueFailures();
        assertThat(state("exhausted-message"))
                .containsEntry("status", "NEEDS_ATTENTION")
                .containsEntry("attempts", 3)
                .containsEntry("next_attempt_at", null)
                .containsEntry("claim_owner", null)
                .containsEntry("claim_until", null);
    }

    private ConsumerFailureRetryCoordinator coordinator(
            ConsumerFailureRetryHandler handler,
            int maximumAttempts) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ConsumerFailureObservability observability = new ConsumerFailureObservability(
                new SimpleMeterRegistry(),
                "inventory-service",
                mapper,
                clock);
        return new ConsumerFailureRetryCoordinator(
                "inventory-service",
                mapper,
                observability,
                maximumAttempts,
                20,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                "inventory-test-worker",
                List.of(handler));
    }

    private ConsumerFailureRetryHandler handler(CheckedPayloadConsumer action) {
        return new ConsumerFailureRetryHandler() {
            @Override
            public String consumerGroup() {
                return GROUP;
            }

            @Override
            public void retry(String rawPayload) throws Exception {
                action.accept(rawPayload);
            }
        };
    }

    private void insertRetry(String messageId, int attempts, Instant nextAttemptAt) {
        insertFailure(messageId, attempts, "RETRYING", nextAttemptAt);
    }

    private void insertFailure(
            String messageId,
            int attempts,
            String status,
            Instant nextAttemptAt) {
        assertThat(mapper.insertIfAbsent(
                messageId,
                GROUP,
                "{\"messageId\":\"" + messageId + "\"}",
                attempts,
                status,
                "test failure",
                NOW.minusSeconds(60),
                nextAttemptAt))
                .isEqualTo(1);
    }

    private Map<String, Object> state(String messageId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, attempts, next_attempt_at, claim_owner, claim_until
                FROM consumer_failure
                WHERE message_id = ? AND consumer_group = ?
                """,
                messageId,
                GROUP);
    }

    @FunctionalInterface
    private interface CheckedPayloadConsumer {
        void accept(String payload) throws Exception;
    }
}
