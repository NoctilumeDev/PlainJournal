package com.ecommerce.marketing;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleOrderResultCommand;
import com.ecommerce.marketing.application.service.FlashSaleResultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class FlashSaleResultIntegrationTest {

    private final FlashSaleResultHandler resultHandler;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    FlashSaleResultIntegrationTest(
            FlashSaleResultHandler resultHandler,
            JdbcTemplate jdbcTemplate) {
        this.resultHandler = resultHandler;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM consumer_failure");
        jdbcTemplate.update("DELETE FROM flash_sale_outbox_event");
        jdbcTemplate.update("DELETE FROM flash_sale_admission");
        jdbcTemplate.update("DELETE FROM flash_sale_activity");
    }

    @Test
    void successResultAdvancesIdempotently() {
        insertAdmission("FST-success", "FSA-1", 101L, "QUEUED");
        FlashSaleOrderResultCommand first = success(
                UUID.randomUUID().toString(), "FST-success", "FSA-1", 101L, "ORD-1");

        resultHandler.handle(first);
        resultHandler.handle(first);
        assertThatThrownBy(() -> resultHandler.handle(success(
                first.eventId(), "FST-success", "FSA-1", 101L, "ORD-conflict")))
                .isInstanceOf(MarketingException.class)
                .extracting(exception -> ((MarketingException) exception).error())
                .isEqualTo(MarketingError.IDEMPOTENCY_CONFLICT);
        resultHandler.handle(success(
                UUID.randomUUID().toString(), "FST-success", "FSA-1", 101L, "ORD-1"));

        assertThat(status("FST-success")).isEqualTo("ORDER_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_no FROM flash_sale_admission WHERE request_token = 'FST-success'",
                String.class)).isEqualTo("ORD-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flash_sale_admission WHERE request_token = 'FST-success'",
                Integer.class)).isOne();
        assertThat(consumedCount()).isEqualTo(2);
    }

    @Test
    void failureResultAdvancesOnlyItsOwnAdmission() {
        insertAdmission("FST-failed", "FSA-2", 201L, "QUEUED");
        insertAdmission("FST-other", "FSA-2", 202L, "QUEUED");

        resultHandler.handle(failure(
                UUID.randomUUID().toString(),
                "FST-failed",
                "FSA-2",
                201L,
                "OUT_OF_STOCK"));

        assertThat(status("FST-failed")).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM flash_sale_admission WHERE request_token = 'FST-failed'",
                String.class)).isEqualTo("OUT_OF_STOCK");
        assertThat(status("FST-other")).isEqualTo("QUEUED");
    }

    @Test
    void mismatchedUserIsRejectedWithoutConsumingTheEvent() {
        insertAdmission("FST-owner", "FSA-3", 301L, "QUEUED");
        insertAdmission("FST-neighbor", "FSA-3", 302L, "QUEUED");
        FlashSaleOrderResultCommand wrongOwner = success(
                UUID.randomUUID().toString(),
                "FST-owner",
                "FSA-3",
                302L,
                "ORD-wrong");

        assertThatThrownBy(() -> resultHandler.handle(wrongOwner))
                .isInstanceOf(MarketingException.class)
                .extracting(exception -> ((MarketingException) exception).error())
                .isEqualTo(MarketingError.IDEMPOTENCY_CONFLICT);

        assertThat(status("FST-owner")).isEqualTo("QUEUED");
        assertThat(status("FST-neighbor")).isEqualTo("QUEUED");
        assertThat(consumedCount()).isZero();
    }

    @Test
    void lateSuccessOverridesResultUnknown() {
        insertAdmission("FST-late", "FSA-4", 401L, "RESULT_UNKNOWN");

        resultHandler.handle(success(
                UUID.randomUUID().toString(),
                "FST-late",
                "FSA-4",
                401L,
                "ORD-late"));

        assertThat(status("FST-late")).isEqualTo("ORDER_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM flash_sale_admission WHERE request_token = 'FST-late'",
                String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT completed_at FROM flash_sale_admission WHERE request_token = 'FST-late'",
                Instant.class)).isNotNull();
    }

    @Test
    void resultCannotSkipThePendingAdmissionAndOutboxBoundary() {
        insertAdmission("FST-pending", "FSA-pending", 450L, "ADMISSION_PENDING");

        assertThatThrownBy(() -> resultHandler.handle(success(
                        UUID.randomUUID().toString(),
                        "FST-pending",
                        "FSA-pending",
                        450L,
                        "ORD-must-not-exist")))
                .isInstanceOf(MarketingException.class)
                .extracting(exception -> ((MarketingException) exception).error())
                .isEqualTo(MarketingError.IDEMPOTENCY_CONFLICT);

        assertThat(status("FST-pending")).isEqualTo("ADMISSION_PENDING");
        assertThat(consumedCount()).isZero();
    }

    @Test
    void conflictingTerminalResultIsRejectedAndRolledBack() {
        insertAdmission("FST-conflict", "FSA-5", 501L, "QUEUED");
        resultHandler.handle(success(
                UUID.randomUUID().toString(),
                "FST-conflict",
                "FSA-5",
                501L,
                "ORD-final"));

        FlashSaleOrderResultCommand conflict = failure(
                UUID.randomUUID().toString(),
                "FST-conflict",
                "FSA-5",
                501L,
                "OUT_OF_STOCK");
        assertThatThrownBy(() -> resultHandler.handle(conflict))
                .isInstanceOf(MarketingException.class)
                .extracting(exception -> ((MarketingException) exception).error())
                .isEqualTo(MarketingError.IDEMPOTENCY_CONFLICT);

        assertThat(status("FST-conflict")).isEqualTo("ORDER_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_no FROM flash_sale_admission WHERE request_token = 'FST-conflict'",
                String.class)).isEqualTo("ORD-final");
        assertThat(consumedCount()).isOne();
    }

    private void insertAdmission(
            String requestToken,
            String activityNo,
            long userId,
            String status) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        INSERT INTO flash_sale_admission
                            (id, request_token, activity_no, user_id, address_id, request_hash,
                             status, remaining_admissions, order_no, failure_code, version,
                             accepted_at, completed_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, ?, NULL, ?, ?)
                        """,
                Math.abs(UUID.randomUUID().getMostSignificantBits()),
                requestToken,
                activityNo,
                userId,
                501L,
                "a".repeat(64),
                status,
                "ADMISSION_PENDING".equals(status) ? null : 10,
                "RESULT_UNKNOWN".equals(status) ? "PROCESSING_TIMEOUT" : null,
                "ADMISSION_PENDING".equals(status) ? null : now.minusSeconds(60),
                now,
                now);
    }

    private FlashSaleOrderResultCommand success(
            String eventId,
            String requestToken,
            String activityNo,
            long userId,
            String orderNo) {
        return new FlashSaleOrderResultCommand(
                eventId,
                "FlashSaleOrderSucceeded",
                requestToken,
                activityNo,
                userId,
                orderNo,
                null,
                Instant.now());
    }

    private FlashSaleOrderResultCommand failure(
            String eventId,
            String requestToken,
            String activityNo,
            long userId,
            String failureCode) {
        return new FlashSaleOrderResultCommand(
                eventId,
                "FlashSaleOrderFailed",
                requestToken,
                activityNo,
                userId,
                null,
                failureCode,
                Instant.now());
    }

    private String status(String requestToken) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_admission WHERE request_token = ?",
                String.class,
                requestToken);
    }

    private int consumedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE consumer_group = ?",
                Integer.class,
                FlashSaleResultHandler.CONSUMER_GROUP);
    }
}
