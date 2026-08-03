package com.ecommerce.analytics.infrastructure.persistence;

import com.ecommerce.analytics.application.model.AnalyticsModels.DailySummary;
import com.ecommerce.analytics.application.model.AnalyticsModels.DomainEvent;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductContribution;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductLine;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductSummary;
import com.ecommerce.analytics.application.model.AnalyticsModels.RebuildView;
import com.ecommerce.platform.common.observability.ConsumerFailureEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class AnalyticsRepository implements ConsumerFailureRetryStore {

    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant currentTime() {
        return jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP(3)",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
    }

    public void lockProjection() {
        jdbc.queryForObject(
                "SELECT id FROM analytics_projection_guard WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    public SourceIdentity findSourceByEventId(String eventId) {
        return first(jdbc.query("""
                SELECT event_id, event_type, producer, aggregate_type, aggregate_id,
                       aggregate_version, payload_hash
                FROM analytics_source_event
                WHERE event_id = ?
                """, sourceIdentityRowMapper(), eventId));
    }

    public SourceIdentity findSourceByLogicalIdentity(DomainEvent event) {
        return first(jdbc.query("""
                SELECT event_id, event_type, producer, aggregate_type, aggregate_id,
                       aggregate_version, payload_hash
                FROM analytics_source_event
                WHERE producer = ?
                  AND aggregate_type = ?
                  AND aggregate_id = ?
                  AND aggregate_version = ?
                  AND event_type = ?
                """,
                sourceIdentityRowMapper(),
                event.producer(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.eventType()));
    }

    public void insertSourceEvent(
            DomainEvent event,
            String consumerGroup,
            LocalDate businessDate,
            Instant consumedAt) {
        jdbc.update("""
                INSERT INTO analytics_source_event
                    (event_id, consumer_group, event_type, producer, aggregate_type,
                     aggregate_id, aggregate_version, occurred_at, business_date,
                     user_id, order_no, amount, payload_hash, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.eventId(),
                consumerGroup,
                event.eventType(),
                event.producer(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                timestamp(event.occurredAt()),
                Date.valueOf(businessDate),
                event.userId(),
                event.orderNo(),
                event.amount(),
                event.fingerprint(),
                timestamp(consumedAt));
    }

    public void insertProductLine(String eventId, ProductLine line) {
        jdbc.update("""
                INSERT INTO analytics_source_product_line
                    (event_id, line_no, product_id, sku_id, product_title,
                     sku_code, quantity, payable_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                line.lineNo(),
                line.productId(),
                line.skuId(),
                line.productTitle(),
                line.skuCode(),
                line.quantity(),
                line.payableAmount());
    }

    public void incrementDaily(
            LocalDate businessDate,
            String eventType,
            BigDecimal amount,
            Instant now) {
        switch (eventType) {
            case "OrderCreated" -> incrementDailyCountAndAmount(
                    businessDate,
                    "created_order_count",
                    "created_order_amount",
                    amount,
                    now);
            case "PaymentSucceeded" -> incrementDailyCountAndAmount(
                    businessDate,
                    "payment_count",
                    "payment_amount",
                    amount,
                    now);
            case "OrderCompleted" -> incrementDailyCountAndAmount(
                    businessDate,
                    "completed_order_count",
                    "completed_order_amount",
                    amount,
                    now);
            case "OrderClosed" -> incrementDailyCount(
                    businessDate,
                    "closed_order_count",
                    now);
            case "AfterSaleApplied" -> incrementDailyCountAndAmount(
                    businessDate,
                    "after_sale_count",
                    "after_sale_amount",
                    amount,
                    now);
            case "RefundSucceeded" -> incrementDailyCountAndAmount(
                    businessDate,
                    "refund_count",
                    "refund_amount",
                    amount,
                    now);
            default -> throw new IllegalArgumentException(
                    "Unsupported analytics event type: " + eventType);
        }
    }

    public void incrementProduct(
            LocalDate businessDate,
            ProductContribution contribution,
            Instant now) {
        jdbc.update("""
                INSERT INTO analytics_product_summary
                    (business_date, product_id, product_title, completed_order_count,
                     units_sold, net_revenue, revenue_covered_order_count, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    product_title = VALUES(product_title),
                    completed_order_count = completed_order_count + 1,
                    units_sold = units_sold + VALUES(units_sold),
                    net_revenue = net_revenue + VALUES(net_revenue),
                    revenue_covered_order_count =
                        revenue_covered_order_count + VALUES(revenue_covered_order_count),
                    updated_at = VALUES(updated_at)
                """,
                Date.valueOf(businessDate),
                contribution.productId(),
                contribution.productTitle(),
                contribution.units(),
                contribution.netRevenue(),
                contribution.revenueCovered() ? 1 : 0,
                timestamp(now));
    }

    public List<DailySummary> selectDaily(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT business_date, created_order_count, created_order_amount,
                       payment_count, payment_amount, completed_order_count,
                       completed_order_amount, closed_order_count, after_sale_count,
                       after_sale_amount, refund_count, refund_amount, updated_at
                FROM analytics_daily_summary
                WHERE business_date BETWEEN ? AND ?
                ORDER BY business_date ASC
                """, dailyRowMapper(), Date.valueOf(from), Date.valueOf(to));
    }

    public List<ProductSummary> selectTopProducts(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                SELECT product_id,
                       MAX(product_title) AS product_title,
                       SUM(completed_order_count) AS completed_order_count,
                       SUM(units_sold) AS units_sold,
                       SUM(net_revenue) AS net_revenue,
                       SUM(revenue_covered_order_count) AS revenue_covered_order_count
                FROM analytics_product_summary
                WHERE business_date BETWEEN ? AND ?
                GROUP BY product_id
                ORDER BY net_revenue DESC, units_sold DESC, product_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new ProductSummary(
                        rs.getLong("product_id"),
                        rs.getString("product_title"),
                        rs.getLong("completed_order_count"),
                        rs.getLong("units_sold"),
                        rs.getBigDecimal("net_revenue"),
                        rs.getLong("revenue_covered_order_count")),
                Date.valueOf(from),
                Date.valueOf(to),
                limit);
    }

    public long countUniqueCustomers(LocalDate from, LocalDate to) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT user_id)
                FROM analytics_source_event
                WHERE business_date BETWEEN ? AND ?
                  AND event_type = 'OrderCreated'
                """, Long.class, Date.valueOf(from), Date.valueOf(to));
        return value == null ? 0 : value;
    }

    public long countSourceEvents(LocalDate from, LocalDate to) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analytics_source_event
                WHERE business_date BETWEEN ? AND ?
                """, Long.class, Date.valueOf(from), Date.valueOf(to));
        return value == null ? 0 : value;
    }

    public Instant selectLastConsumedAt(LocalDate from, LocalDate to) {
        Timestamp value = jdbc.queryForObject("""
                SELECT MAX(consumed_at)
                FROM analytics_source_event
                WHERE business_date BETWEEN ? AND ?
                """, Timestamp.class, Date.valueOf(from), Date.valueOf(to));
        return instant(value);
    }

    public ProjectionRows<DailySummary> selectExpectedDaily(
            LocalDate from,
            LocalDate to,
            int rowLimit) {
        List<DailySummary> rows = jdbc.query(expectedDailySql(), dailyRowMapper(),
                Date.valueOf(from), Date.valueOf(to), rowLimit + 1);
        return bounded(rows, rowLimit);
    }

    public ProjectionRows<DailySummary> selectActualDaily(
            LocalDate from,
            LocalDate to,
            int rowLimit) {
        List<DailySummary> rows = jdbc.query("""
                SELECT business_date, created_order_count, created_order_amount,
                       payment_count, payment_amount, completed_order_count,
                       completed_order_amount, closed_order_count, after_sale_count,
                       after_sale_amount, refund_count, refund_amount, updated_at
                FROM analytics_daily_summary
                WHERE business_date BETWEEN ? AND ?
                ORDER BY business_date ASC
                LIMIT ?
                """, dailyRowMapper(), Date.valueOf(from), Date.valueOf(to), rowLimit + 1);
        return bounded(rows, rowLimit);
    }

    public ProjectionRows<ProductProjectionRow> selectExpectedProducts(
            LocalDate from,
            LocalDate to,
            int rowLimit) {
        List<ProductProjectionRow> rows = jdbc.query(expectedProductSql(),
                productProjectionRowMapper(),
                Date.valueOf(from),
                Date.valueOf(to),
                rowLimit + 1);
        return bounded(rows, rowLimit);
    }

    public ProjectionRows<ProductProjectionRow> selectActualProducts(
            LocalDate from,
            LocalDate to,
            int rowLimit) {
        List<ProductProjectionRow> rows = jdbc.query("""
                SELECT business_date, product_id, product_title,
                       completed_order_count, units_sold, net_revenue,
                       revenue_covered_order_count, updated_at
                FROM analytics_product_summary
                WHERE business_date BETWEEN ? AND ?
                ORDER BY business_date ASC, product_id ASC
                LIMIT ?
                """,
                productProjectionRowMapper(),
                Date.valueOf(from),
                Date.valueOf(to),
                rowLimit + 1);
        return bounded(rows, rowLimit);
    }

    public void rebuildRange(LocalDate from, LocalDate to) {
        Date fromDate = Date.valueOf(from);
        Date toDate = Date.valueOf(to);
        jdbc.update(
                "DELETE FROM analytics_product_summary WHERE business_date BETWEEN ? AND ?",
                fromDate,
                toDate);
        jdbc.update(
                "DELETE FROM analytics_daily_summary WHERE business_date BETWEEN ? AND ?",
                fromDate,
                toDate);
        jdbc.update("""
                INSERT INTO analytics_daily_summary
                    (business_date, created_order_count, created_order_amount,
                     payment_count, payment_amount, completed_order_count,
                     completed_order_amount, closed_order_count, after_sale_count,
                     after_sale_amount, refund_count, refund_amount, updated_at)
                """ + expectedDailySqlWithoutLimit(),
                fromDate,
                toDate);
        jdbc.update("""
                INSERT INTO analytics_product_summary
                    (business_date, product_id, product_title, completed_order_count,
                     units_sold, net_revenue, revenue_covered_order_count, updated_at)
                """ + expectedProductSqlWithoutLimit(),
                fromDate,
                toDate);
    }

    public RebuildAudit findRebuildAudit(String commandId) {
        return first(jdbc.query("""
                SELECT command_id, request_hash, operator_id, reason, from_date, to_date,
                       source_event_count, before_issue_count, after_issue_count, created_at
                FROM analytics_rebuild_audit
                WHERE command_id = ?
                """,
                (rs, rowNum) -> new RebuildAudit(
                        rs.getString("command_id"),
                        rs.getString("request_hash"),
                        rs.getLong("operator_id"),
                        rs.getString("reason"),
                        rs.getDate("from_date").toLocalDate(),
                        rs.getDate("to_date").toLocalDate(),
                        rs.getLong("source_event_count"),
                        rs.getLong("before_issue_count"),
                        rs.getLong("after_issue_count"),
                        instant(rs.getTimestamp("created_at"))),
                commandId));
    }

    public void insertRebuildAudit(
            String commandId,
            String requestHash,
            long operatorId,
            String reason,
            LocalDate from,
            LocalDate to,
            long sourceEventCount,
            long beforeIssueCount,
            long afterIssueCount,
            Instant now) {
        jdbc.update("""
                INSERT INTO analytics_rebuild_audit
                    (command_id, request_hash, operator_id, reason, from_date, to_date,
                     source_event_count, before_issue_count, after_issue_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                commandId,
                requestHash,
                operatorId,
                reason,
                Date.valueOf(from),
                Date.valueOf(to),
                sourceEventCount,
                beforeIssueCount,
                afterIssueCount,
                timestamp(now));
    }

    public boolean insertConsumerFailureIfAbsent(
            String messageId,
            String consumerGroup,
            String payload,
            int attempts,
            String status,
            String error,
            Instant now,
            Instant nextAttemptAt) {
        return jdbc.update("""
                INSERT IGNORE INTO consumer_failure
                    (message_id, consumer_group, raw_payload, attempts, status, last_error,
                     first_failed_at, last_failed_at, recovered_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                messageId,
                consumerGroup,
                payload,
                attempts,
                status,
                error,
                timestamp(now),
                timestamp(now),
                timestamp(nextAttemptAt)) == 1;
    }

    public boolean markConsumerFailed(
            String messageId,
            String consumerGroup,
            int attempts,
            String status,
            String error,
            Instant now,
            Instant nextAttemptAt) {
        return jdbc.update("""
                UPDATE consumer_failure
                SET attempts = GREATEST(attempts, ?),
                    status = ?,
                    last_error = ?,
                    last_failed_at = ?,
                    recovered_at = NULL,
                    next_attempt_at = CASE
                        WHEN ? = 'NEEDS_ATTENTION' THEN NULL
                        WHEN next_attempt_at IS NULL OR next_attempt_at > ?
                            THEN ?
                        ELSE next_attempt_at
                    END,
                    claimed_at = NULL,
                    claim_owner = NULL,
                    claim_until = NULL
                WHERE message_id = ?
                  AND consumer_group = ?
                  AND status = 'RETRYING'
                  AND (claim_until IS NULL OR claim_until <= ?)
                """,
                attempts,
                status,
                error,
                timestamp(now),
                status,
                timestamp(nextAttemptAt),
                timestamp(nextAttemptAt),
                messageId,
                consumerGroup,
                timestamp(now)) == 1;
    }

    public boolean markConsumerRecovered(
            String messageId,
            String consumerGroup,
            Instant now) {
        return jdbc.update("""
                UPDATE consumer_failure
                SET status = 'RECOVERED',
                    recovered_at = ?,
                    next_attempt_at = NULL,
                    claimed_at = NULL,
                    claim_owner = NULL,
                    claim_until = NULL
                WHERE message_id = ? AND consumer_group = ? AND status <> 'RECOVERED'
                """, timestamp(now), messageId, consumerGroup) == 1;
    }

    @Override
    public List<ConsumerFailureRetryEntry> selectRetryable(Instant now, int limit) {
        return jdbc.query("""
                SELECT message_id, consumer_group, raw_payload, attempts
                FROM consumer_failure
                WHERE status = 'RETRYING'
                  AND next_attempt_at <= ?
                  AND (claim_until IS NULL OR claim_until <= ?)
                ORDER BY next_attempt_at, message_id
                LIMIT ?
                """, (rs, rowNum) -> {
            ConsumerFailureRetryEntry entry = new ConsumerFailureRetryEntry();
            entry.setMessageId(rs.getString("message_id"));
            entry.setConsumerGroup(rs.getString("consumer_group"));
            entry.setRawPayload(rs.getString("raw_payload"));
            entry.setAttempts(rs.getInt("attempts"));
            return entry;
        }, timestamp(now), timestamp(now), limit);
    }

    @Override
    public int claimRetry(
            String messageId,
            String consumerGroup,
            String owner,
            int expectedAttempts,
            Instant now,
            Instant claimUntil) {
        return jdbc.update("""
                UPDATE consumer_failure
                SET claimed_at = ?,
                    claim_owner = ?,
                    claim_until = ?
                WHERE message_id = ?
                  AND consumer_group = ?
                  AND status = 'RETRYING'
                  AND attempts = ?
                  AND next_attempt_at <= ?
                  AND (claim_until IS NULL OR claim_until <= ?)
                """,
                timestamp(now),
                owner,
                timestamp(claimUntil),
                messageId,
                consumerGroup,
                expectedAttempts,
                timestamp(now),
                timestamp(now));
    }

    @Override
    public int markRetryRecovered(
            String messageId,
            String consumerGroup,
            String owner,
            Instant now) {
        return jdbc.update("""
                UPDATE consumer_failure
                SET status = 'RECOVERED',
                    recovered_at = ?,
                    next_attempt_at = NULL,
                    claimed_at = NULL,
                    claim_owner = NULL,
                    claim_until = NULL
                WHERE message_id = ?
                  AND consumer_group = ?
                  AND status = 'RETRYING'
                  AND claim_owner = ?
                  AND claim_until > ?
                """,
                timestamp(now),
                messageId,
                consumerGroup,
                owner,
                timestamp(now));
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
        return jdbc.update("""
                UPDATE consumer_failure
                SET attempts = GREATEST(attempts, ?),
                    status = ?,
                    last_error = ?,
                    last_failed_at = ?,
                    recovered_at = NULL,
                    next_attempt_at = ?,
                    claimed_at = NULL,
                    claim_owner = NULL,
                    claim_until = NULL
                WHERE message_id = ?
                  AND consumer_group = ?
                  AND status = 'RETRYING'
                  AND claim_owner = ?
                  AND claim_until > ?
                """,
                attempts,
                status,
                error,
                timestamp(now),
                timestamp(nextAttemptAt),
                messageId,
                consumerGroup,
                owner,
                timestamp(now));
    }

    @Override
    public long countByStatus(String status) {
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumer_failure WHERE status = ?",
                Long.class,
                status);
        return result == null ? 0 : result;
    }

    @Override
    public Instant selectOldestActiveFailedAt() {
        Timestamp value = jdbc.queryForObject("""
                SELECT MIN(first_failed_at)
                FROM consumer_failure
                WHERE status IN ('RETRYING', 'NEEDS_ATTENTION')
                """, Timestamp.class);
        return instant(value);
    }

    @Override
    public List<ConsumerFailureEntry> selectRecentActive(int limit) {
        return jdbc.query("""
                SELECT message_id, consumer_group, attempts, status, last_error,
                       first_failed_at, last_failed_at
                FROM consumer_failure
                WHERE status IN ('RETRYING', 'NEEDS_ATTENTION')
                ORDER BY last_failed_at DESC, message_id ASC
                LIMIT ?
                """, (rs, rowNum) -> {
            ConsumerFailureEntry entry = new ConsumerFailureEntry();
            entry.setMessageId(rs.getString("message_id"));
            entry.setConsumerGroup(rs.getString("consumer_group"));
            entry.setAttempts(rs.getInt("attempts"));
            entry.setStatus(rs.getString("status"));
            entry.setLastError(rs.getString("last_error"));
            entry.setFirstFailedAt(instant(rs.getTimestamp("first_failed_at")));
            entry.setLastFailedAt(instant(rs.getTimestamp("last_failed_at")));
            return entry;
        }, limit);
    }

    private void incrementDailyCountAndAmount(
            LocalDate businessDate,
            String countColumn,
            String amountColumn,
            BigDecimal amount,
            Instant now) {
        String sql = """
                INSERT INTO analytics_daily_summary
                    (business_date, __COUNT_COLUMN__, __AMOUNT_COLUMN__, updated_at)
                VALUES (?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    __COUNT_COLUMN__ = __COUNT_COLUMN__ + 1,
                    __AMOUNT_COLUMN__ = __AMOUNT_COLUMN__ + VALUES(__AMOUNT_COLUMN__),
                    updated_at = VALUES(updated_at)
                """
                .replace("__COUNT_COLUMN__", countColumn)
                .replace("__AMOUNT_COLUMN__", amountColumn);
        jdbc.update(sql, Date.valueOf(businessDate), amount, timestamp(now));
    }

    private void incrementDailyCount(
            LocalDate businessDate,
            String countColumn,
            Instant now) {
        String sql = """
                INSERT INTO analytics_daily_summary
                    (business_date, __COUNT_COLUMN__, updated_at)
                VALUES (?, 1, ?)
                ON DUPLICATE KEY UPDATE
                    __COUNT_COLUMN__ = __COUNT_COLUMN__ + 1,
                    updated_at = VALUES(updated_at)
                """.replace("__COUNT_COLUMN__", countColumn);
        jdbc.update(sql, Date.valueOf(businessDate), timestamp(now));
    }

    private String expectedDailySql() {
        return expectedDailySqlWithoutLimit() + " LIMIT ?";
    }

    private String expectedDailySqlWithoutLimit() {
        return """
                SELECT business_date,
                       SUM(CASE WHEN event_type = 'OrderCreated' THEN 1 ELSE 0 END)
                           AS created_order_count,
                       SUM(CASE WHEN event_type = 'OrderCreated' THEN amount ELSE 0 END)
                           AS created_order_amount,
                       SUM(CASE WHEN event_type = 'PaymentSucceeded' THEN 1 ELSE 0 END)
                           AS payment_count,
                       SUM(CASE WHEN event_type = 'PaymentSucceeded' THEN amount ELSE 0 END)
                           AS payment_amount,
                       SUM(CASE WHEN event_type = 'OrderCompleted' THEN 1 ELSE 0 END)
                           AS completed_order_count,
                       SUM(CASE WHEN event_type = 'OrderCompleted' THEN amount ELSE 0 END)
                           AS completed_order_amount,
                       SUM(CASE WHEN event_type = 'OrderClosed' THEN 1 ELSE 0 END)
                           AS closed_order_count,
                       SUM(CASE WHEN event_type = 'AfterSaleApplied' THEN 1 ELSE 0 END)
                           AS after_sale_count,
                       SUM(CASE WHEN event_type = 'AfterSaleApplied' THEN amount ELSE 0 END)
                           AS after_sale_amount,
                       SUM(CASE WHEN event_type = 'RefundSucceeded' THEN 1 ELSE 0 END)
                           AS refund_count,
                       SUM(CASE WHEN event_type = 'RefundSucceeded' THEN amount ELSE 0 END)
                           AS refund_amount,
                       MAX(consumed_at) AS updated_at
                FROM analytics_source_event
                WHERE business_date BETWEEN ? AND ?
                GROUP BY business_date
                ORDER BY business_date ASC
                """;
    }

    private String expectedProductSql() {
        return expectedProductSqlWithoutLimit() + " LIMIT ?";
    }

    private String expectedProductSqlWithoutLimit() {
        return """
                SELECT business_date, product_id, MAX(product_title) AS product_title,
                       COUNT(*) AS completed_order_count,
                       SUM(units_sold) AS units_sold,
                       SUM(net_revenue) AS net_revenue,
                       SUM(revenue_covered) AS revenue_covered_order_count,
                       MAX(updated_at) AS updated_at
                FROM (
                    SELECT e.business_date, l.product_id, e.event_id,
                           MAX(l.product_title) AS product_title,
                           SUM(l.quantity) AS units_sold,
                           CASE
                               WHEN COUNT(*) = COUNT(l.payable_amount)
                               THEN SUM(l.payable_amount)
                               ELSE 0
                           END AS net_revenue,
                           CASE
                               WHEN COUNT(*) = COUNT(l.payable_amount)
                               THEN 1
                               ELSE 0
                           END AS revenue_covered,
                           MAX(e.consumed_at) AS updated_at
                    FROM analytics_source_event e
                    JOIN analytics_source_product_line l ON l.event_id = e.event_id
                    WHERE e.business_date BETWEEN ? AND ?
                      AND e.event_type = 'OrderCompleted'
                    GROUP BY e.business_date, l.product_id, e.event_id
                ) product_event
                GROUP BY business_date, product_id
                ORDER BY business_date ASC, product_id ASC
                """;
    }

    private RowMapper<DailySummary> dailyRowMapper() {
        return (rs, rowNum) -> new DailySummary(
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("created_order_count"),
                rs.getBigDecimal("created_order_amount"),
                rs.getLong("payment_count"),
                rs.getBigDecimal("payment_amount"),
                rs.getLong("completed_order_count"),
                rs.getBigDecimal("completed_order_amount"),
                rs.getLong("closed_order_count"),
                rs.getLong("after_sale_count"),
                rs.getBigDecimal("after_sale_amount"),
                rs.getLong("refund_count"),
                rs.getBigDecimal("refund_amount"),
                instant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<ProductProjectionRow> productProjectionRowMapper() {
        return (rs, rowNum) -> new ProductProjectionRow(
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("product_id"),
                rs.getString("product_title"),
                rs.getLong("completed_order_count"),
                rs.getLong("units_sold"),
                money(rs.getBigDecimal("net_revenue")),
                rs.getLong("revenue_covered_order_count"),
                instant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<SourceIdentity> sourceIdentityRowMapper() {
        return (rs, rowNum) -> new SourceIdentity(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("producer"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getLong("aggregate_version"),
                rs.getString("payload_hash"));
    }

    private <T> ProjectionRows<T> bounded(List<T> rows, int limit) {
        boolean saturated = rows.size() > limit;
        List<T> boundedRows = saturated ? rows.subList(0, limit) : rows;
        return new ProjectionRows<>(List.copyOf(boundedRows), saturated);
    }

    private <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record SourceIdentity(
            String eventId,
            String eventType,
            String producer,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String fingerprint) {
    }

    public record ProductProjectionRow(
            LocalDate businessDate,
            long productId,
            String productTitle,
            long completedOrderCount,
            long unitsSold,
            BigDecimal netRevenue,
            long revenueCoveredOrderCount,
            Instant updatedAt) {
    }

    public record ProjectionRows<T>(List<T> rows, boolean saturated) {

        public ProjectionRows {
            rows = List.copyOf(rows);
        }
    }

    public record RebuildAudit(
            String commandId,
            String requestHash,
            long operatorId,
            String reason,
            LocalDate from,
            LocalDate to,
            long sourceEventCount,
            long beforeIssueCount,
            long afterIssueCount,
            Instant createdAt) {

        public RebuildView view() {
            return new RebuildView(
                    commandId,
                    operatorId,
                    reason,
                    from,
                    to,
                    sourceEventCount,
                    beforeIssueCount,
                    afterIssueCount,
                    createdAt);
        }
    }
}
