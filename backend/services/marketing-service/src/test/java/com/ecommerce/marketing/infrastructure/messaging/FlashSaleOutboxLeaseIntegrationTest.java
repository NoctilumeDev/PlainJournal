package com.ecommerce.marketing.infrastructure.messaging;

import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleOutboxEventEntity;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleOutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class FlashSaleOutboxLeaseIntegrationTest {

    private static final String AGGREGATE_TYPE = "FlashSaleOutboxLeaseTest";
    private static final Instant BASE_TIME = Instant.parse("2026-07-25T00:00:00Z");

    private final FlashSaleOutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    FlashSaleOutboxLeaseIntegrationTest(
            FlashSaleOutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate) {
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update(
                "DELETE FROM flash_sale_outbox_event WHERE aggregate_type = ?",
                AGGREGATE_TYPE);
    }

    @Test
    void fencesExpiredOwnersAndRejectsAStaleAttemptsSnapshot() {
        String eventId = insertEvent();
        Instant firstClaimedAt = BASE_TIME.plusSeconds(1);
        FlashSaleOutboxEventEntity staleSelection = outboxMapper
                .selectClaimCandidates(firstClaimedAt, 20)
                .get(0);

        assertThat(outboxMapper.claim(
                eventId,
                "flash-owner-a",
                staleSelection.getAttempts(),
                firstClaimedAt,
                firstClaimedAt.plusSeconds(10))).isEqualTo(1);
        assertThat(outboxMapper.markPublished(
                eventId,
                "flash-owner-a",
                firstClaimedAt.plusSeconds(11))).isZero();
        assertThat(outboxMapper.markFailed(
                eventId,
                "flash-owner-a",
                firstClaimedAt.plusSeconds(30),
                "late failure",
                firstClaimedAt.plusSeconds(11))).isZero();

        Instant recoveredAt = firstClaimedAt.plusSeconds(11);
        assertThat(outboxMapper.claim(
                eventId,
                "flash-owner-b",
                staleSelection.getAttempts(),
                recoveredAt,
                recoveredAt.plusSeconds(10))).isEqualTo(1);
        Instant nextAttemptAt = recoveredAt.plusSeconds(30);
        assertThat(outboxMapper.markFailed(
                eventId,
                "flash-owner-b",
                nextAttemptAt,
                "fault injected",
                recoveredAt.plusSeconds(1))).isEqualTo(1);

        assertThat(outboxMapper.claim(
                eventId,
                "flash-owner-c",
                staleSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(11))).isZero();

        FlashSaleOutboxEventEntity freshSelection = outboxMapper
                .selectClaimCandidates(nextAttemptAt.plusSeconds(1), 20)
                .get(0);
        assertThat(freshSelection.getAttempts()).isEqualTo(1);
        assertThat(outboxMapper.claim(
                eventId,
                "flash-owner-c",
                freshSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(11))).isEqualTo(1);
    }

    private String insertEvent() {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO flash_sale_outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version,
                     payload, status, attempts, next_attempt_at, claim_owner, claim_until,
                     published_at, last_error, created_at, updated_at)
                VALUES
                    (?, 'FlashSaleLeaseEvent', ?, ?, 1, '{}', 'PENDING', 0, ?,
                     NULL, NULL, NULL, NULL, ?, ?)
                """,
                eventId,
                AGGREGATE_TYPE,
                "flash-lease-" + UUID.randomUUID(),
                BASE_TIME,
                BASE_TIME,
                BASE_TIME);
        return eventId;
    }
}
