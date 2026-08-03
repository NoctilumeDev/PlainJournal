package com.ecommerce.notification.infrastructure.persistence;

import com.ecommerce.notification.application.model.NotificationModels.DeliveryRetryView;
import com.ecommerce.notification.application.model.NotificationModels.EmailDeliveryAttempt;
import com.ecommerce.notification.application.model.NotificationModels.EmailPreferenceView;
import com.ecommerce.notification.application.model.NotificationModels.NotificationView;
import com.ecommerce.platform.common.observability.ConsumerFailureEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class NotificationRepository implements ConsumerFailureRetryStore {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
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

    public void insertTask(
            long taskId,
            String sourceEventId,
            String sourceEventType,
            String templateCode,
            long userId,
            String referenceType,
            String referenceNo,
            String title,
            String content,
            Instant now) {
        jdbc.update("""
                INSERT INTO notification_task
                    (id, source_event_id, source_event_type, template_code, user_id,
                     reference_type, reference_no, title, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId, sourceEventId, sourceEventType, templateCode, userId,
                referenceType, referenceNo, title, content, timestamp(now));
    }

    public void insertInApp(long notificationId, long taskId, long userId, Instant now) {
        jdbc.update("""
                INSERT INTO in_app_notification
                    (id, task_id, user_id, status, read_at, created_at, updated_at)
                VALUES (?, ?, ?, 'UNREAD', NULL, ?, ?)
                """, notificationId, taskId, userId, timestamp(now), timestamp(now));
    }

    public void insertEmailDelivery(
            long deliveryId,
            long taskId,
            String destination,
            String providerMessageId,
            Instant now) {
        jdbc.update("""
                INSERT INTO notification_delivery
                    (id, task_id, channel, destination, status, attempts, next_attempt_at,
                     claim_owner, claim_until, provider_message_id, sent_at, last_error,
                     created_at, updated_at)
                VALUES (?, ?, 'EMAIL', ?, 'PENDING', 0, ?, NULL, NULL, ?, NULL, NULL, ?, ?)
                """,
                deliveryId, taskId, destination, timestamp(now), providerMessageId,
                timestamp(now), timestamp(now));
    }

    public EmailPreferenceView findPreference(long userId) {
        List<EmailPreferenceView> rows = jdbc.query("""
                SELECT user_id, email, email_enabled, updated_at
                FROM notification_recipient
                WHERE user_id = ?
                """, emailPreferenceRowMapper(), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EmailPreferenceView savePreference(long userId, String email, boolean enabled, Instant now) {
        jdbc.update("""
                INSERT INTO notification_recipient
                    (user_id, email, email_enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    email = VALUES(email),
                    email_enabled = VALUES(email_enabled),
                    updated_at = VALUES(updated_at)
                """, userId, email, enabled, timestamp(now), timestamp(now));
        return findPreference(userId);
    }

    public List<NotificationView> listNotifications(
            long userId,
            Instant beforeCreatedAt,
            Long beforeId,
            int limit) {
        String cursorPredicate = beforeCreatedAt == null
                ? ""
                : " AND (n.created_at < ? OR (n.created_at = ? AND n.id < ?))";
        String sql = """
                SELECT n.id, t.template_code, t.reference_type, t.reference_no,
                       t.title, t.content, n.status, n.read_at, n.created_at
                FROM in_app_notification n
                JOIN notification_task t ON t.id = n.task_id
                WHERE n.user_id = ?
                """ + cursorPredicate + """
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT ?
                """;
        if (beforeCreatedAt == null) {
            return jdbc.query(sql, notificationRowMapper(), userId, limit);
        }
        Timestamp cursorTime = timestamp(beforeCreatedAt);
        return jdbc.query(sql, notificationRowMapper(),
                userId, cursorTime, cursorTime, beforeId, limit);
    }

    public long countUnread(long userId) {
        Long result = jdbc.queryForObject("""
                SELECT COUNT(*) FROM in_app_notification
                WHERE user_id = ? AND status = 'UNREAD'
                """, Long.class, userId);
        return result == null ? 0 : result;
    }

    public boolean notificationExists(long notificationId, long userId) {
        Long result = jdbc.queryForObject("""
                SELECT COUNT(*) FROM in_app_notification
                WHERE id = ? AND user_id = ?
                """, Long.class, notificationId, userId);
        return result != null && result > 0;
    }

    public void markRead(long notificationId, long userId, Instant now) {
        jdbc.update("""
                UPDATE in_app_notification
                SET status = 'READ',
                    read_at = COALESCE(read_at, ?),
                    updated_at = ?
                WHERE id = ? AND user_id = ?
                """, timestamp(now), timestamp(now), notificationId, userId);
    }

    public List<EmailDeliveryAttempt> selectDueEmailDeliveriesForUpdate(
            Instant now,
            int limit) {
        String sql = """
                SELECT d.id, d.attempts, d.destination, d.provider_message_id,
                       t.title, t.content
                FROM notification_delivery d
                JOIN notification_task t ON t.id = d.task_id
                WHERE d.channel = 'EMAIL'
                  AND (
                    (d.status IN ('PENDING', 'RETRY') AND d.next_attempt_at <= ?)
                    OR (d.status = 'SENDING' AND d.claim_until <= ?)
                )
                ORDER BY d.next_attempt_at ASC, d.id ASC
                LIMIT __LIMIT__
                FOR UPDATE SKIP LOCKED
                """.replace("__LIMIT__", Integer.toString(limit));
        return jdbc.query(sql, (rs, rowNum) -> new EmailDeliveryAttempt(
                rs.getLong("id"),
                rs.getInt("attempts"),
                rs.getString("destination"),
                rs.getString("provider_message_id"),
                rs.getString("title"),
                rs.getString("content")),
                timestamp(now), timestamp(now));
    }

    public boolean claimEmailDelivery(
            long deliveryId,
            int expectedAttempts,
            String workerId,
            Instant claimUntil,
            Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery
                SET status = 'SENDING',
                    attempts = attempts + 1,
                    claim_owner = ?,
                    claim_until = ?,
                    updated_at = ?
                WHERE id = ?
                  AND channel = 'EMAIL'
                  AND attempts = ?
                  AND (
                    (status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?)
                    OR (status = 'SENDING' AND claim_until <= ?)
                  )
                """,
                workerId,
                timestamp(claimUntil),
                timestamp(now),
                deliveryId,
                expectedAttempts,
                timestamp(now),
                timestamp(now)) == 1;
    }

    public boolean markDeliverySent(
            long deliveryId,
            String workerId,
            int expectedAttempts,
            Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery
                SET status = 'SENT',
                    sent_at = ?,
                    claim_owner = NULL,
                    claim_until = NULL,
                    last_error = NULL,
                    updated_at = ?
                WHERE id = ? AND status = 'SENDING' AND claim_owner = ?
                  AND attempts = ? AND claim_until > ?
                """,
                timestamp(now),
                timestamp(now),
                deliveryId,
                workerId,
                expectedAttempts,
                timestamp(now)) == 1;
    }

    public boolean markDeliveryFailed(
            long deliveryId,
            String workerId,
            int expectedAttempts,
            String status,
            Instant nextAttemptAt,
            String error,
            Instant now) {
        return jdbc.update("""
                UPDATE notification_delivery
                SET status = ?,
                    next_attempt_at = ?,
                    claim_owner = NULL,
                    claim_until = NULL,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'SENDING' AND claim_owner = ?
                  AND attempts = ? AND claim_until > ?
                """,
                status,
                timestamp(nextAttemptAt),
                error,
                timestamp(now),
                deliveryId,
                workerId,
                expectedAttempts,
                timestamp(now)) == 1;
    }

    public DeliveryState findDeliveryForUpdate(long deliveryId) {
        List<DeliveryState> rows = jdbc.query("""
                SELECT id, status, attempts, last_error
                FROM notification_delivery
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new DeliveryState(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error")), deliveryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean insertRetryAudit(
            long auditId,
            String commandId,
            long deliveryId,
            long operatorId,
            String reason,
            String beforeStatus,
            String afterStatus,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO notification_delivery_retry_audit
                    (id, command_id, delivery_id, operator_id, reason,
                     before_status, after_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId, commandId, deliveryId, operatorId, reason,
                beforeStatus, afterStatus, timestamp(now)) == 1;
    }

    public DeliveryRetryAudit findRetryAudit(String commandId) {
        List<DeliveryRetryAudit> rows = jdbc.query("""
                SELECT delivery_id, command_id, operator_id, reason,
                       before_status, after_status, created_at
                FROM notification_delivery_retry_audit
                WHERE command_id = ?
                """, (rs, rowNum) -> new DeliveryRetryAudit(
                new DeliveryRetryView(
                        rs.getLong("delivery_id"),
                        rs.getString("command_id"),
                        rs.getString("before_status"),
                        rs.getString("after_status"),
                        instant(rs.getTimestamp("created_at"))),
                rs.getLong("operator_id"),
                rs.getString("reason")), commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void resetDeliveryForRetry(long deliveryId, Instant now) {
        jdbc.update("""
                UPDATE notification_delivery
                SET status = 'RETRY',
                    attempts = 0,
                    next_attempt_at = ?,
                    claim_owner = NULL,
                    claim_until = NULL,
                    updated_at = ?
                WHERE id = ? AND status = 'NEEDS_ATTENTION'
                """, timestamp(now), timestamp(now), deliveryId);
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

    private RowMapper<EmailPreferenceView> emailPreferenceRowMapper() {
        return (rs, rowNum) -> new EmailPreferenceView(
                rs.getLong("user_id"),
                rs.getString("email"),
                rs.getBoolean("email_enabled"),
                instant(rs.getTimestamp("updated_at")));
    }

    private RowMapper<NotificationView> notificationRowMapper() {
        return (rs, rowNum) -> new NotificationView(
                rs.getLong("id"),
                rs.getString("template_code"),
                rs.getString("reference_type"),
                rs.getString("reference_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                instant(rs.getTimestamp("read_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record DeliveryState(long id, String status, int attempts, String lastError) {
    }

    public record DeliveryRetryAudit(
            DeliveryRetryView view,
            long operatorId,
            String reason) {
    }
}
