package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class ChatOutboxLeaseIntegrationTest {

    private static final String AGGREGATE_TYPE = "ChatOutboxLeaseTest";
    private static final Instant BASE_TIME = Instant.parse("2026-07-23T00:00:00Z");

    private final OutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ChatOutboxLeaseIntegrationTest(
            OutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate) {
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    @Test
    void fencesExpiredOwnerAndPreservesAggregateOrder() {
        String aggregateId = "chat-lease-" + UUID.randomUUID();
        String firstId = insertEvent(aggregateId, 1, BASE_TIME);
        String secondId = insertEvent(aggregateId, 2, BASE_TIME.plusSeconds(1));

        assertThat(publishableIds(BASE_TIME.plusSeconds(2))).containsExactly(firstId);
        Instant leaseUntil = BASE_TIME.plusSeconds(10);
        assertThat(outboxMapper.claim(
                firstId,
                "chat-owner-a",
                0,
                BASE_TIME.plusSeconds(2),
                leaseUntil)).isEqualTo(1);
        assertThat(publishableIds(BASE_TIME.plusSeconds(3))).isEmpty();
        assertThat(outboxMapper.markPublished(
                firstId,
                "chat-owner-a",
                leaseUntil.plusMillis(1))).isZero();
        assertThat(outboxMapper.resetStaleClaims(leaseUntil.plusMillis(1))).isEqualTo(1);

        Instant recoveredAt = leaseUntil.plusSeconds(1);
        assertThat(outboxMapper.claim(
                firstId,
                "chat-owner-b",
                0,
                recoveredAt,
                recoveredAt.plusSeconds(30))).isEqualTo(1);
        assertThat(outboxMapper.markPublished(
                firstId,
                "chat-owner-b",
                recoveredAt.plusSeconds(1))).isEqualTo(1);
        assertThat(publishableIds(recoveredAt.plusSeconds(2))).containsExactly(secondId);
    }

    @Test
    void rejectsAStaleSelectionAfterARescheduledAttemptBecomesDue() {
        String aggregateId = "chat-stale-selection-" + UUID.randomUUID();
        String eventId = insertEvent(aggregateId, 1, BASE_TIME);
        Instant firstClaimedAt = BASE_TIME.plusSeconds(1);
        OutboxEventEntity staleSelection = outboxMapper
                .selectPublishable(firstClaimedAt, 20)
                .get(0);

        assertThat(outboxMapper.claim(
                eventId,
                "chat-owner-a",
                staleSelection.getAttempts(),
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30))).isEqualTo(1);
        Instant nextAttemptAt = firstClaimedAt.plusSeconds(60);
        assertThat(outboxMapper.markFailed(
                eventId,
                "chat-owner-a",
                nextAttemptAt,
                "fault injected",
                firstClaimedAt.plusSeconds(1))).isEqualTo(1);

        assertThat(outboxMapper.claim(
                eventId,
                "chat-owner-b",
                staleSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isZero();

        OutboxEventEntity freshSelection = outboxMapper
                .selectPublishable(nextAttemptAt.plusSeconds(1), 20)
                .get(0);
        assertThat(freshSelection.getAttempts()).isEqualTo(1);
        assertThat(outboxMapper.claim(
                eventId,
                "chat-owner-b",
                freshSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isEqualTo(1);
    }

    private String insertEvent(String aggregateId, int version, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     destination_topic, payload, status, attempts, next_attempt_at,
                     claimed_at, claim_owner, claim_until, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    (?, 'ChatLeaseEvent', ?, ?, ?, 'ecommerce-chat-events-test', '{}',
                     'PENDING', 0, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                """,
                id,
                AGGREGATE_TYPE,
                aggregateId,
                version,
                createdAt,
                createdAt,
                createdAt);
        return id;
    }

    private List<String> publishableIds(Instant now) {
        return outboxMapper.selectPublishable(now, 20).stream()
                .map(OutboxEventEntity::getId)
                .toList();
    }
}
