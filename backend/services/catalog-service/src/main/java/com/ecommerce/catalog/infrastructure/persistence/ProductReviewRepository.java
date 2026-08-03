package com.ecommerce.catalog.infrastructure.persistence;

import com.ecommerce.catalog.application.model.ReviewModels.ModerationResultView;
import com.ecommerce.catalog.application.model.ReviewModels.OrderCompletedEvent;
import com.ecommerce.catalog.application.model.ReviewModels.OrderLineSnapshot;
import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewEligibilityView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReplyView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewSummaryView;
import com.ecommerce.platform.common.observability.ConsumerFailureEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class ProductReviewRepository implements ConsumerFailureRetryStore {

    private final JdbcTemplate jdbc;

    public ProductReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant currentTime() {
        return jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP(3)",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
    }

    public boolean insertConsumed(
            String eventId,
            String consumerGroup,
            String payloadFingerprint,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO consumed_event
                    (event_id, consumer_group, payload_fingerprint, consumed_at)
                VALUES (?, ?, ?, ?)
                """, eventId, consumerGroup, payloadFingerprint, timestamp(now)) == 1;
    }

    public String findConsumedFingerprint(String eventId, String consumerGroup) {
        List<String> rows = jdbc.query("""
                SELECT payload_fingerprint
                FROM consumed_event
                WHERE event_id = ?
                  AND consumer_group = ?
                """, (rs, rowNum) -> rs.getString("payload_fingerprint"), eventId, consumerGroup);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertEligibility(
            long id,
            OrderCompletedEvent event,
            OrderLineSnapshot item,
            Instant now) {
        jdbc.update("""
                INSERT IGNORE INTO review_eligibility
                    (id, source_event_id, order_no, line_no, user_id, product_id, sku_id,
                     product_title, sku_code, sku_name, spec_json, image_object_key, quantity,
                     completed_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ELIGIBLE', ?, ?)
                """,
                id, event.eventId(), event.orderNo(), item.lineNo(), event.userId(),
                item.productId(), item.skuId(), item.productTitle(), item.skuCode(),
                item.skuName(), item.specJson(), item.imageObjectKey(), item.quantity(),
                timestamp(event.completedAt()), timestamp(now), timestamp(now));
    }

    public List<ReviewEligibilityView> listEligibilities(long userId, String orderNo) {
        String orderPredicate = orderNo == null ? "" : " AND e.order_no = ?";
        String sql = """
                SELECT e.id, e.order_no, e.line_no, e.product_id, e.sku_id,
                       e.product_title, e.sku_code, e.sku_name, e.spec_json,
                       e.image_object_key, e.quantity, e.status, r.id AS review_id,
                       e.completed_at
                FROM review_eligibility e
                LEFT JOIN product_review r ON r.eligibility_id = e.id
                WHERE e.user_id = ?
                """ + orderPredicate + """
                ORDER BY e.completed_at DESC, e.order_no DESC, e.line_no ASC
                """;
        Object[] arguments = orderNo == null
                ? new Object[]{userId}
                : new Object[]{userId, orderNo};
        return jdbc.query(sql, (rs, rowNum) -> new ReviewEligibilityView(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getInt("line_no"),
                rs.getLong("product_id"),
                rs.getLong("sku_id"),
                rs.getString("product_title"),
                rs.getString("sku_code"),
                rs.getString("sku_name"),
                rs.getString("spec_json"),
                rs.getString("image_object_key"),
                rs.getLong("quantity"),
                rs.getString("status"),
                nullableLong(rs.getObject("review_id")),
                instant(rs.getTimestamp("completed_at"))), arguments);
    }

    public EligibilityState findEligibilityForUpdate(long eligibilityId, long userId) {
        List<EligibilityState> rows = jdbc.query("""
                SELECT id, order_no, line_no, user_id, product_id, sku_id, status
                FROM review_eligibility
                WHERE id = ? AND user_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new EligibilityState(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getInt("line_no"),
                rs.getLong("user_id"),
                rs.getLong("product_id"),
                rs.getLong("sku_id"),
                rs.getString("status")), eligibilityId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReviewState findReviewByIdempotency(long userId, String idempotencyKey) {
        return singleReviewState("""
                SELECT id, eligibility_id, user_id, product_id, sku_id, rating, status,
                       idempotency_key, request_hash, like_count
                FROM product_review
                WHERE user_id = ? AND idempotency_key = ?
                """, userId, idempotencyKey);
    }

    public ReviewState findReviewByEligibility(long eligibilityId) {
        return singleReviewState("""
                SELECT id, eligibility_id, user_id, product_id, sku_id, rating, status,
                       idempotency_key, request_hash, like_count
                FROM product_review
                WHERE eligibility_id = ?
                """, eligibilityId);
    }

    public ReviewState findReviewForUpdate(long reviewId) {
        return singleReviewState("""
                SELECT id, eligibility_id, user_id, product_id, sku_id, rating, status,
                       idempotency_key, request_hash, like_count
                FROM product_review
                WHERE id = ?
                FOR UPDATE
                """, reviewId);
    }

    public void insertReview(
            long reviewId,
            EligibilityState eligibility,
            int rating,
            String content,
            boolean anonymous,
            String idempotencyKey,
            String requestHash,
            Instant now) {
        jdbc.update("""
                INSERT INTO product_review
                    (id, eligibility_id, order_no, line_no, user_id, product_id, sku_id,
                     rating, content, anonymous, status, idempotency_key, request_hash,
                     like_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, 0, ?, ?)
                """,
                reviewId, eligibility.id(), eligibility.orderNo(), eligibility.lineNo(),
                eligibility.userId(), eligibility.productId(), eligibility.skuId(),
                rating, content, anonymous, idempotencyKey, requestHash,
                timestamp(now), timestamp(now));
    }

    public void markEligibilityReviewed(long eligibilityId, Instant now) {
        jdbc.update("""
                UPDATE review_eligibility
                SET status = 'REVIEWED', updated_at = ?
                WHERE id = ? AND status = 'ELIGIBLE'
                """, timestamp(now), eligibilityId);
    }

    public void incrementSummary(long productId, int rating, Instant now) {
        String column = ratingColumn(rating);
        jdbc.update("""
                INSERT INTO product_review_summary
                    (product_id, review_count, rating_sum,
                     rating_1_count, rating_2_count, rating_3_count,
                     rating_4_count, rating_5_count, updated_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    review_count = review_count + 1,
                    rating_sum = rating_sum + VALUES(rating_sum),
                    __RATING_COLUMN__ = __RATING_COLUMN__ + 1,
                    updated_at = VALUES(updated_at)
                """.replace("__RATING_COLUMN__", column),
                productId,
                rating,
                rating == 1 ? 1 : 0,
                rating == 2 ? 1 : 0,
                rating == 3 ? 1 : 0,
                rating == 4 ? 1 : 0,
                rating == 5 ? 1 : 0,
                timestamp(now));
    }

    public void decrementSummary(long productId, int rating, Instant now) {
        String column = ratingColumn(rating);
        jdbc.update("""
                UPDATE product_review_summary
                SET review_count = review_count - 1,
                    rating_sum = rating_sum - ?,
                    __RATING_COLUMN__ = __RATING_COLUMN__ - 1,
                    updated_at = ?
                WHERE product_id = ?
                  AND review_count > 0
                  AND rating_sum >= ?
                  AND __RATING_COLUMN__ > 0
                """.replace("__RATING_COLUMN__", column),
                rating, timestamp(now), productId, rating);
    }

    public ReviewSummaryView findSummary(long productId) {
        List<ReviewSummaryView> rows = jdbc.query("""
                SELECT product_id, review_count, rating_sum,
                       rating_1_count, rating_2_count, rating_3_count,
                       rating_4_count, rating_5_count
                FROM product_review_summary
                WHERE product_id = ?
                """, (rs, rowNum) -> {
            long count = rs.getLong("review_count");
            BigDecimal average = count == 0
                    ? BigDecimal.ZERO.setScale(1)
                    : BigDecimal.valueOf(rs.getLong("rating_sum"))
                            .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
            return new ReviewSummaryView(
                    rs.getLong("product_id"),
                    count,
                    average,
                    rs.getLong("rating_1_count"),
                    rs.getLong("rating_2_count"),
                    rs.getLong("rating_3_count"),
                    rs.getLong("rating_4_count"),
                    rs.getLong("rating_5_count"));
        }, productId);
        return rows.isEmpty()
                ? new ReviewSummaryView(productId, 0, BigDecimal.ZERO.setScale(1),
                        0, 0, 0, 0, 0)
                : rows.get(0);
    }

    public boolean activeProductExists(long productId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_spu
                WHERE id = ? AND status = 'ACTIVE'
                """, Long.class, productId);
        return count != null && count > 0;
    }

    public long countPublishedReviews(long productId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_review
                WHERE product_id = ? AND status = 'PUBLISHED'
                """, Long.class, productId);
        return count == null ? 0 : count;
    }

    public List<ProductReviewView> listPublishedReviews(
            long productId,
            Long viewerId,
            long offset,
            int limit) {
        return jdbc.query("""
                SELECT r.id, r.product_id, r.sku_id, e.sku_name, e.spec_json,
                       r.rating, r.content, r.anonymous, r.status, r.like_count,
                       r.created_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM review_like l
                           WHERE l.review_id = r.id AND l.user_id = ?
                       ) THEN TRUE ELSE FALSE END AS liked_by_viewer,
                       reply.id AS reply_id, reply.content AS reply_content,
                       reply.created_at AS reply_created_at
                FROM product_review r
                JOIN review_eligibility e ON e.id = r.eligibility_id
                LEFT JOIN review_reply reply ON reply.review_id = r.id
                WHERE r.product_id = ? AND r.status = 'PUBLISHED'
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?
                """, reviewViewMapper(), viewerId, productId, limit, offset);
    }

    public ProductReviewView findReviewView(long reviewId, Long viewerId) {
        List<ProductReviewView> rows = jdbc.query("""
                SELECT r.id, r.product_id, r.sku_id, e.sku_name, e.spec_json,
                       r.rating, r.content, r.anonymous, r.status, r.like_count,
                       r.created_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM review_like l
                           WHERE l.review_id = r.id AND l.user_id = ?
                       ) THEN TRUE ELSE FALSE END AS liked_by_viewer,
                       reply.id AS reply_id, reply.content AS reply_content,
                       reply.created_at AS reply_created_at
                FROM product_review r
                JOIN review_eligibility e ON e.id = r.eligibility_id
                LEFT JOIN review_reply reply ON reply.review_id = r.id
                WHERE r.id = ?
                """, reviewViewMapper(), viewerId, reviewId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean insertLike(long reviewId, long userId, Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO review_like (review_id, user_id, created_at)
                VALUES (?, ?, ?)
                """, reviewId, userId, timestamp(now)) == 1;
    }

    public boolean deleteLike(long reviewId, long userId) {
        return jdbc.update("""
                DELETE FROM review_like WHERE review_id = ? AND user_id = ?
                """, reviewId, userId) == 1;
    }

    public void incrementLikeCount(long reviewId, Instant now) {
        jdbc.update("""
                UPDATE product_review
                SET like_count = like_count + 1, updated_at = ?
                WHERE id = ? AND status = 'PUBLISHED'
                """, timestamp(now), reviewId);
    }

    public void decrementLikeCount(long reviewId, Instant now) {
        jdbc.update("""
                UPDATE product_review
                SET like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END,
                    updated_at = ?
                WHERE id = ?
                """, timestamp(now), reviewId);
    }

    public boolean insertReport(
            long reportId,
            long reviewId,
            long reporterId,
            String reasonCode,
            String detail,
            String requestHash,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO review_report
                    (id, review_id, reporter_user_id, reason_code, detail, request_hash,
                     status, resolution, resolved_by, resolved_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', NULL, NULL, NULL, ?, ?)
                """,
                reportId, reviewId, reporterId, reasonCode, detail, requestHash,
                timestamp(now), timestamp(now)) == 1;
    }

    public ReportState findReportByReporter(long reviewId, long reporterId) {
        return singleReportState("""
                SELECT id, review_id, reporter_user_id, request_hash, status, resolution
                FROM review_report
                WHERE review_id = ? AND reporter_user_id = ?
                """, reviewId, reporterId);
    }

    public ReportState findReportForUpdate(long reportId) {
        return singleReportState("""
                SELECT id, review_id, reporter_user_id, request_hash, status, resolution
                FROM review_report
                WHERE id = ?
                FOR UPDATE
                """, reportId);
    }

    public long countReports(String status) {
        String predicate = status == null ? "" : " WHERE rp.status = ?";
        Long count = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM review_report rp", Long.class)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM review_report rp" + predicate,
                        Long.class,
                        status);
        return count == null ? 0 : count;
    }

    public List<ReviewReportView> listReports(
            String status,
            long offset,
            int limit) {
        String predicate = status == null ? "" : " WHERE rp.status = ?";
        String sql = """
                SELECT rp.id, rp.review_id, r.product_id, r.rating,
                       r.content AS review_content, rp.reason_code, rp.detail,
                       rp.status, rp.resolution, rp.created_at, rp.resolved_at
                FROM review_report rp
                JOIN product_review r ON r.id = rp.review_id
                """ + predicate + """
                ORDER BY
                    CASE WHEN rp.status = 'OPEN' THEN 0 ELSE 1 END,
                    rp.created_at ASC,
                    rp.id ASC
                LIMIT ? OFFSET ?
                """;
        Object[] arguments = status == null
                ? new Object[]{limit, offset}
                : new Object[]{status, limit, offset};
        return jdbc.query(sql, (rs, rowNum) -> new ReviewReportView(
                rs.getLong("id"),
                rs.getLong("review_id"),
                rs.getLong("product_id"),
                rs.getInt("rating"),
                rs.getString("review_content"),
                rs.getString("reason_code"),
                rs.getString("detail"),
                rs.getString("status"),
                rs.getString("resolution"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("resolved_at"))), arguments);
    }

    public ReplyState findReplyByCommand(String commandId) {
        return singleReplyState("""
                SELECT id, review_id, operator_id, command_id, request_hash, content, created_at
                FROM review_reply
                WHERE command_id = ?
                """, commandId);
    }

    public ReplyState findReplyByReview(long reviewId) {
        return singleReplyState("""
                SELECT id, review_id, operator_id, command_id, request_hash, content, created_at
                FROM review_reply
                WHERE review_id = ?
                """, reviewId);
    }

    public boolean insertReply(
            long replyId,
            long reviewId,
            long operatorId,
            String content,
            String commandId,
            String requestHash,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO review_reply
                    (id, review_id, operator_id, content, command_id, request_hash,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                replyId, reviewId, operatorId, content, commandId, requestHash,
                timestamp(now), timestamp(now)) == 1;
    }

    public ModerationAuditState findModerationAudit(String commandId) {
        List<ModerationAuditState> rows = jdbc.query("""
                SELECT command_id, report_id, review_id, operator_id, resolution, request_hash,
                       review_status_before, review_status_after, created_at
                FROM review_moderation_audit
                WHERE command_id = ?
                """, (rs, rowNum) -> new ModerationAuditState(
                rs.getString("command_id"),
                rs.getLong("report_id"),
                rs.getLong("review_id"),
                rs.getLong("operator_id"),
                rs.getString("resolution"),
                rs.getString("request_hash"),
                rs.getString("review_status_before"),
                rs.getString("review_status_after"),
                instant(rs.getTimestamp("created_at"))), commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean insertModerationAudit(
            long auditId,
            String commandId,
            long reportId,
            long reviewId,
            long operatorId,
            String resolution,
            String reason,
            String requestHash,
            String beforeStatus,
            String afterStatus,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO review_moderation_audit
                    (id, command_id, report_id, review_id, operator_id, resolution,
                     reason, request_hash, review_status_before, review_status_after, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId, commandId, reportId, reviewId, operatorId, resolution,
                reason, requestHash, beforeStatus, afterStatus, timestamp(now)) == 1;
    }

    public void resolveReport(
            long reportId,
            long operatorId,
            String resolution,
            Instant now) {
        jdbc.update("""
                UPDATE review_report
                SET status = 'RESOLVED', resolution = ?, resolved_by = ?,
                    resolved_at = ?, updated_at = ?
                WHERE id = ? AND status = 'OPEN'
                """,
                resolution, operatorId, timestamp(now), timestamp(now), reportId);
    }

    public void hideReview(long reviewId, Instant now) {
        jdbc.update("""
                UPDATE product_review
                SET status = 'HIDDEN', updated_at = ?
                WHERE id = ? AND status = 'PUBLISHED'
                """, timestamp(now), reviewId);
    }

    public ModerationResultView moderationResult(ModerationAuditState audit) {
        return new ModerationResultView(
                audit.reportId(),
                audit.reviewId(),
                audit.commandId(),
                audit.resolution(),
                audit.reviewStatusBefore(),
                audit.reviewStatusAfter(),
                audit.createdAt());
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
                messageId, consumerGroup, payload, attempts, status, error,
                timestamp(now), timestamp(now), timestamp(nextAttemptAt)) == 1;
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
                attempts, status, error, timestamp(now), status,
                timestamp(nextAttemptAt), timestamp(nextAttemptAt),
                messageId, consumerGroup, timestamp(now)) == 1;
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
                timestamp(now), owner, timestamp(claimUntil),
                messageId, consumerGroup, expectedAttempts,
                timestamp(now), timestamp(now));
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
                timestamp(now), messageId, consumerGroup, owner, timestamp(now));
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
                attempts, status, error, timestamp(now), timestamp(nextAttemptAt),
                messageId, consumerGroup, owner, timestamp(now));
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

    private org.springframework.jdbc.core.RowMapper<ProductReviewView> reviewViewMapper() {
        return (rs, rowNum) -> {
            Long replyId = nullableLong(rs.getObject("reply_id"));
            ReviewReplyView reply = replyId == null
                    ? null
                    : new ReviewReplyView(
                            replyId,
                            rs.getString("reply_content"),
                            instant(rs.getTimestamp("reply_created_at")));
            boolean anonymous = rs.getBoolean("anonymous");
            return new ProductReviewView(
                    rs.getLong("id"),
                    rs.getLong("product_id"),
                    rs.getLong("sku_id"),
                    rs.getString("sku_name"),
                    rs.getString("spec_json"),
                    rs.getInt("rating"),
                    rs.getString("content"),
                    anonymous,
                    anonymous ? "Anonymous verified customer" : "Verified customer",
                    rs.getString("status"),
                    rs.getLong("like_count"),
                    rs.getBoolean("liked_by_viewer"),
                    reply,
                    instant(rs.getTimestamp("created_at")));
        };
    }

    private ReviewState singleReviewState(String sql, Object... arguments) {
        List<ReviewState> rows = jdbc.query(sql, (rs, rowNum) -> new ReviewState(
                rs.getLong("id"),
                rs.getLong("eligibility_id"),
                rs.getLong("user_id"),
                rs.getLong("product_id"),
                rs.getLong("sku_id"),
                rs.getInt("rating"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getLong("like_count")), arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ReportState singleReportState(String sql, Object... arguments) {
        List<ReportState> rows = jdbc.query(sql, (rs, rowNum) -> new ReportState(
                rs.getLong("id"),
                rs.getLong("review_id"),
                rs.getLong("reporter_user_id"),
                rs.getString("request_hash"),
                rs.getString("status"),
                rs.getString("resolution")), arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ReplyState singleReplyState(String sql, Object... arguments) {
        List<ReplyState> rows = jdbc.query(sql, (rs, rowNum) -> new ReplyState(
                rs.getLong("id"),
                rs.getLong("review_id"),
                rs.getLong("operator_id"),
                rs.getString("command_id"),
                rs.getString("request_hash"),
                rs.getString("content"),
                instant(rs.getTimestamp("created_at"))), arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String ratingColumn(int rating) {
        return switch (rating) {
            case 1 -> "rating_1_count";
            case 2 -> "rating_2_count";
            case 3 -> "rating_3_count";
            case 4 -> "rating_4_count";
            case 5 -> "rating_5_count";
            default -> throw new IllegalArgumentException("Rating must be between 1 and 5");
        };
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record EligibilityState(
            long id,
            String orderNo,
            int lineNo,
            long userId,
            long productId,
            long skuId,
            String status) {
    }

    public record ReviewState(
            long id,
            long eligibilityId,
            long userId,
            long productId,
            long skuId,
            int rating,
            String status,
            String idempotencyKey,
            String requestHash,
            long likeCount) {
    }

    public record ReportState(
            long id,
            long reviewId,
            long reporterUserId,
            String requestHash,
            String status,
            String resolution) {
    }

    public record ReplyState(
            long id,
            long reviewId,
            long operatorId,
            String commandId,
            String requestHash,
            String content,
            Instant createdAt) {
    }

    public record ModerationAuditState(
            String commandId,
            long reportId,
            long reviewId,
            long operatorId,
            String resolution,
            String requestHash,
            String reviewStatusBefore,
            String reviewStatusAfter,
            Instant createdAt) {
    }
}
