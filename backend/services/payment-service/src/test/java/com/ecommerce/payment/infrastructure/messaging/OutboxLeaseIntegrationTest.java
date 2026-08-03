package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class OutboxLeaseIntegrationTest {

    private static final String AGGREGATE_TYPE = "PaymentOutboxLeaseTest";
    private static final Instant BASE_TIME = Instant.parse("2026-07-21T00:00:00Z");

    private final OutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    OutboxLeaseIntegrationTest(OutboxEventMapper outboxMapper, JdbcTemplate jdbcTemplate) {
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    @Test
    void fencesExpiredOwnerAndDoesNotExposeAggregateSuccessorEarly() {
        String aggregateId = "payment-lease-" + UUID.randomUUID();
        String firstId = insertEvent(aggregateId, 1, BASE_TIME);
        String secondId = insertEvent(aggregateId, 2, BASE_TIME.plusSeconds(1));

        assertThat(publishableIds(BASE_TIME.plusSeconds(2))).containsExactly(firstId);
        Instant firstLeaseUntil = BASE_TIME.plusSeconds(10);
        assertThat(outboxMapper.claim(
                firstId, "payment-owner-a", 0, BASE_TIME.plusSeconds(2), firstLeaseUntil)).isEqualTo(1);
        assertThat(publishableIds(BASE_TIME.plusSeconds(3))).isEmpty();

        assertThat(outboxMapper.markPublished(
                firstId, "payment-owner-a", firstLeaseUntil.plusMillis(1))).isZero();
        assertThat(outboxMapper.markFailed(
                firstId, "payment-owner-a", firstLeaseUntil.plusSeconds(1),
                "late owner", firstLeaseUntil.plusMillis(1))).isZero();
        assertThat(outboxMapper.resetStaleClaims(
                firstLeaseUntil.plusMillis(1), firstLeaseUntil.plusMillis(1))).isEqualTo(1);

        Instant recoveredAt = firstLeaseUntil.plusSeconds(1);
        assertThat(outboxMapper.claim(
                firstId, "payment-owner-b", 0, recoveredAt, recoveredAt.plusSeconds(30))).isEqualTo(1);
        assertThat(outboxMapper.markPublished(firstId, "payment-owner-b", recoveredAt.plusSeconds(1)))
                .isEqualTo(1);
        assertThat(publishableIds(recoveredAt.plusSeconds(2))).containsExactly(secondId);

        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status, claim_owner, claim_until FROM outbox_event WHERE id = ?", firstId);
        assertThat(state.get("status")).isEqualTo("PUBLISHED");
        assertThat(state.get("claim_owner")).isNull();
        assertThat(state.get("claim_until")).isNull();
    }

    @Test
    void rejectsAStaleSelectionBeforeAndAfterTheRescheduledAttemptBecomesDue() {
        String aggregateId = "payment-stale-selection-" + UUID.randomUUID();
        String eventId = insertEvent(aggregateId, 1, BASE_TIME);
        Instant firstClaimedAt = BASE_TIME.plusSeconds(1);
        OutboxEventEntity staleSelection = outboxMapper
                .selectPublishable(firstClaimedAt, 20)
                .get(0);

        assertThat(outboxMapper.claim(
                eventId,
                "payment-owner-a",
                staleSelection.getAttempts(),
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30))).isEqualTo(1);
        Instant nextAttemptAt = firstClaimedAt.plusSeconds(60);
        assertThat(outboxMapper.markFailed(
                eventId,
                "payment-owner-a",
                nextAttemptAt,
                "fault injected",
                firstClaimedAt.plusSeconds(1))).isEqualTo(1);

        assertThat(outboxMapper.claim(
                eventId,
                "payment-owner-b",
                staleSelection.getAttempts(),
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(32))).isZero();
        assertThat(outboxMapper.claim(
                eventId,
                "payment-owner-b",
                staleSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isZero();

        OutboxEventEntity freshSelection = outboxMapper
                .selectPublishable(nextAttemptAt.plusSeconds(1), 20)
                .get(0);
        assertThat(freshSelection.getAttempts()).isEqualTo(1);
        assertThat(outboxMapper.claim(
                eventId,
                "payment-owner-b",
                freshSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isEqualTo(1);
    }

    private String insertEvent(String aggregateId, int version, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
                     published_at, last_error, created_at, updated_at)
                VALUES
                    (?, 'PaymentLeaseEvent', ?, ?, ?, '{}', 'PENDING', 0, ?,
                     NULL, NULL, NULL, NULL, NULL, ?, ?)
                """,
                id, AGGREGATE_TYPE, aggregateId, version, createdAt, createdAt, createdAt);
        return id;
    }

    private List<String> publishableIds(Instant now) {
        return outboxMapper.selectPublishable(now, 20).stream()
                .map(OutboxEventEntity::getId)
                .toList();
    }
}
