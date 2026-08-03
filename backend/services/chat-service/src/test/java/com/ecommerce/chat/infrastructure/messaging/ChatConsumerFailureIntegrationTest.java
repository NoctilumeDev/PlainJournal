package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.entity.ConsumerFailureRetryEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConsumerFailureMapper;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class ChatConsumerFailureIntegrationTest {

    private static final String MESSAGE_ID = "chat-consumer-failure-001";
    private static final String CONSUMER_GROUP = "chat-dispatcher-test";

    private final ConsumerFailureRecorder failureRecorder;
    private final ConsumerFailureMapper failureMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    ChatConsumerFailureIntegrationTest(
            ConsumerFailureRecorder failureRecorder,
            ConsumerFailureMapper failureMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.failureRecorder = failureRecorder;
        this.failureMapper = failureMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM consumer_failure");
    }

    @Test
    void persistsRetryingTerminalAndRecoveredStatesWithoutExposingRawPayload() throws Exception {
        MessageView message = message();

        assertThat(failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("temporary database outage")))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumer_failure",
                String.class))
                .isEqualTo("RETRYING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_payload FROM consumer_failure",
                String.class))
                .contains("privatePayloadMarker");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure",
                Timestamp.class))
                .isNotNull();

        assertThat(failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("retry budget exhausted")))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumer_failure",
                String.class))
                .isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM consumer_failure",
                Integer.class))
                .isEqualTo(16);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure",
                Timestamp.class))
                .isNull();

        mockMvc.perform(get("/actuator/consumerfailures"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/consumerfailures")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/consumerfailures?limit=500")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("chat-service"))
                .andExpect(jsonPath("$.retrying").value(0))
                .andExpect(jsonPath("$.needsAttention").value(1))
                .andExpect(jsonPath("$.activeFailures.length()").value(1))
                .andExpect(jsonPath("$.activeFailures[0].messageId").value(MESSAGE_ID))
                .andExpect(jsonPath("$.activeFailures[0].consumerGroup")
                        .value(CONSUMER_GROUP))
                .andExpect(jsonPath("$.activeFailures[0].rawPayload").doesNotExist());
        mockMvc.perform(get("/actuator/prometheus")
                        .header(
                                "X-Metrics-Token",
                                "test-only-metrics-token-with-at-least-32-characters"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "ecommerce_consumer_failure_active_events")));

        failureRecorder.markRecovered(message, CONSUMER_GROUP);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumer_failure",
                String.class))
                .isEqualTo("RECOVERED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure",
                Timestamp.class))
                .isNull();
        mockMvc.perform(get("/actuator/consumerfailures")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAttention").value(0))
                .andExpect(jsonPath("$.recovered").value(1))
                .andExpect(jsonPath("$.activeFailures.length()").value(0));
    }

    @Test
    void claimsRetriesAndPersistsFailureThenRecoveryStateTransitions() {
        String messageId = "chat-consumer-retry-lease-001";
        MessageView message = message(messageId, 1);

        assertThat(failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("temporary Redis outage")))
                .isFalse();
        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();
        Instant claimedAt = nextAttemptAt.plusMillis(1);
        List<ConsumerFailureRetryEntity> retries = failureMapper.selectRetryable(
                CONSUMER_GROUP,
                "chat-delivery-test-chat-test",
                claimedAt,
                20);
        assertThat(retries).extracting(ConsumerFailureRetryEntity::getMessageId)
                .containsExactly(messageId);
        ConsumerFailureRetryEntity retry = retries.get(0);

        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-a",
                retry.getAttempts(),
                claimedAt,
                claimedAt.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-b",
                retry.getAttempts(),
                claimedAt.plusSeconds(1),
                claimedAt.plusSeconds(31)))
                .isZero();

        ConsumerFailureRecorder.RetryFailureResult failed =
                failureRecorder.recordRetryFailure(
                        retry,
                        "worker-a",
                        new IllegalStateException("Redis still unavailable"),
                        false);
        assertThat(failed.updated()).isTrue();
        assertThat(failed.terminal()).isFalse();
        assertThat(failed.attempts()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT status, attempts, next_attempt_at, claim_owner, claim_until
                FROM consumer_failure
                WHERE message_id = ?
                """,
                messageId))
                .containsEntry("status", "RETRYING")
                .containsEntry("attempts", 2)
                .containsEntry("claim_owner", null)
                .containsEntry("claim_until", null);

        Instant rescheduledAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();
        Instant reclaimedAt = rescheduledAt.plusMillis(1);
        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-b",
                2,
                reclaimedAt,
                reclaimedAt.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(failureRecorder.markRetryRecovered(retry, "worker-b")).isTrue();
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT status, next_attempt_at, claim_owner, claim_until, recovered_at
                FROM consumer_failure
                WHERE message_id = ?
                """,
                messageId))
                .containsEntry("status", "RECOVERED")
                .containsEntry("next_attempt_at", null)
                .containsEntry("claim_owner", null)
                .containsEntry("claim_until", null)
                .containsKey("recovered_at");
    }

    @Test
    void letsAnotherWorkerReclaimAnExpiredRetryLease() {
        String messageId = "chat-consumer-retry-lease-expired";
        MessageView message = message(messageId, 1);
        failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("temporary Redis outage"));
        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();
        Instant firstClaimedAt = nextAttemptAt.plusMillis(1);
        Instant firstClaimUntil = firstClaimedAt.plusSeconds(5);

        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-a",
                1,
                firstClaimedAt,
                firstClaimUntil))
                .isEqualTo(1);
        Instant reclaimedAt = firstClaimUntil.plusMillis(1);
        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-b",
                1,
                reclaimedAt,
                reclaimedAt.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(failureMapper.markRetryRecovered(
                messageId,
                CONSUMER_GROUP,
                "worker-a",
                reclaimedAt))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_owner FROM consumer_failure WHERE message_id = ?",
                String.class,
                messageId))
                .isEqualTo("worker-b");
    }

    @Test
    void doesNotRegressRecoveredFailureWhenOriginalMessageArrivesAgain() {
        String messageId = "chat-consumer-retry-recovered";
        MessageView message = message(messageId, 1);
        failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("temporary Redis outage"));
        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();
        ConsumerFailureRetryEntity retry = failureMapper.selectRetryable(
                CONSUMER_GROUP,
                "chat-delivery-test-chat-test",
                nextAttemptAt.plusMillis(1),
                20).get(0);
        Instant claimedAt = nextAttemptAt.plusMillis(1);
        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-a",
                retry.getAttempts(),
                claimedAt,
                claimedAt.plusSeconds(30)))
                .isEqualTo(1);
        assertThat(failureRecorder.markRetryRecovered(retry, "worker-a")).isTrue();

        failureRecorder.record(
                message(messageId, 2),
                CONSUMER_GROUP,
                new IllegalStateException("late duplicate delivery"));

        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT status, attempts, next_attempt_at, claim_owner, claim_until, recovered_at
                FROM consumer_failure
                WHERE message_id = ?
                """,
                messageId))
                .containsEntry("status", "RECOVERED")
                .containsEntry("attempts", 1)
                .containsEntry("next_attempt_at", null)
                .containsEntry("claim_owner", null)
                .containsEntry("claim_until", null)
                .containsKey("recovered_at");
    }

    @Test
    void doesNotLetDuplicateBrokerFailureStealAnActiveMysqlRetryLease() {
        String messageId = "chat-consumer-retry-active-lease";
        MessageView message = message(messageId, 1);
        failureRecorder.record(
                message,
                CONSUMER_GROUP,
                new IllegalStateException("temporary Redis outage"));
        Instant nextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();
        Instant claimedAt = nextAttemptAt.plusMillis(1);
        assertThat(failureMapper.claimRetry(
                messageId,
                CONSUMER_GROUP,
                "worker-a",
                1,
                claimedAt,
                claimedAt.plusSeconds(30)))
                .isEqualTo(1);

        failureRecorder.record(
                message(messageId, 2),
                CONSUMER_GROUP,
                new IllegalStateException("late duplicate delivery"));

        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT status, attempts, claim_owner, claim_until
                FROM consumer_failure
                WHERE message_id = ?
                """,
                messageId))
                .containsEntry("status", "RETRYING")
                .containsEntry("attempts", 1)
                .containsEntry("claim_owner", "worker-a")
                .containsKey("claim_until");
    }

    @Test
    void keepsRetryAttemptsMonotonicAndDoesNotPostponeAnEarlierRetry() {
        String messageId = "chat-consumer-retry-monotonic";
        failureRecorder.record(
                message(messageId, 3),
                CONSUMER_GROUP,
                new IllegalStateException("third delivery attempt"));
        Instant originalNextAttemptAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM consumer_failure WHERE message_id = ?",
                Timestamp.class,
                messageId).toInstant();

        assertThat(failureMapper.markFailed(
                messageId,
                CONSUMER_GROUP,
                1,
                "RETRYING",
                "late first-attempt duplicate",
                originalNextAttemptAt.plusSeconds(1),
                originalNextAttemptAt.plusSeconds(30)))
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT attempts, next_attempt_at
                FROM consumer_failure
                WHERE message_id = ?
                """,
                messageId))
                .containsEntry("attempts", 3)
                .containsEntry("next_attempt_at", Timestamp.from(originalNextAttemptAt));
    }

    private MessageView message() {
        MessageView message = message(MESSAGE_ID, 1);
        when(message.getDeliveryAttempt()).thenReturn(1, 16);
        return message;
    }

    private MessageView message(String messageIdValue, int deliveryAttempt) {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(message.getMessageId()).thenReturn(messageId);
        when(messageId.toString()).thenReturn(messageIdValue);
        when(message.getBody()).thenReturn(ByteBuffer.wrap(
                "{\"privatePayloadMarker\":\"must-not-reach-actuator\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        when(message.getDeliveryAttempt()).thenReturn(deliveryAttempt);
        return message;
    }
}
