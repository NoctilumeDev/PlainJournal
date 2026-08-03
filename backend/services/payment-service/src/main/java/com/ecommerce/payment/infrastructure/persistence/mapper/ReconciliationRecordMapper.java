package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.payment.infrastructure.reconciliation.ReconciliationFinding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ReconciliationRecordMapper extends BaseMapper<ReconciliationRecordEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT domain, reference_no, issue_type
            FROM (
                SELECT 'PAYMENT' AS domain, p.payment_no AS reference_no,
                       'PAYMENT_SUCCESS_INCOMPLETE' AS issue_type
                FROM payment_order p
                WHERE p.status = 'SUCCESS'
                  AND (p.channel_transaction_no IS NULL OR p.paid_at IS NULL)

                UNION ALL

                SELECT 'PAYMENT', p.payment_no, 'PAYMENT_SUCCESS_TRANSACTION_MISMATCH'
                FROM payment_order p
                WHERE p.status = 'SUCCESS'
                  AND NOT EXISTS (
                    SELECT 1 FROM payment_transaction t
                    WHERE t.payment_id = p.id AND t.transaction_type = 'PAYMENT'
                      AND t.status = 'SUCCESS' AND t.channel = p.channel
                      AND t.channel_transaction_no = p.channel_transaction_no
                      AND t.amount = p.amount
                  )

                UNION ALL

                SELECT 'PAYMENT', p.payment_no, 'PAYMENT_SUCCESS_EVENT_MISSING'
                FROM payment_order p
                WHERE p.status = 'SUCCESS'
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = p.payment_no AND o.event_type = 'PaymentSucceeded'
                  )

                UNION ALL

                SELECT 'PAYMENT', p.payment_no, 'PAYMENT_SUCCESS_TRANSACTION_UNEXPECTED'
                FROM payment_order p
                WHERE p.status <> 'SUCCESS'
                  AND EXISTS (
                    SELECT 1 FROM payment_transaction t
                    WHERE t.payment_id = p.id AND t.status = 'SUCCESS'
                  )

                UNION ALL

                SELECT 'PAYMENT', p.payment_no, 'PAYMENT_SUCCESS_TRANSACTION_DUPLICATE'
                FROM payment_order p
                WHERE (
                    SELECT COUNT(*) FROM payment_transaction t
                    WHERE t.payment_id = p.id AND t.transaction_type = 'PAYMENT'
                      AND t.status = 'SUCCESS'
                  ) > 1

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SUCCESS_INCOMPLETE'
                FROM refund_order r
                WHERE r.status = 'SUCCESS'
                  AND (r.channel_refund_no IS NULL OR r.refunded_at IS NULL)

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SUCCESS_TRANSACTION_MISMATCH'
                FROM refund_order r
                WHERE r.status = 'SUCCESS'
                  AND NOT EXISTS (
                    SELECT 1 FROM refund_transaction t
                    WHERE t.refund_id = r.id AND t.status = 'SUCCESS'
                      AND t.channel = r.channel
                      AND t.channel_refund_no = r.channel_refund_no
                      AND t.amount = r.amount
                  )

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SUCCESS_EVENT_MISSING'
                FROM refund_order r
                WHERE r.status = 'SUCCESS'
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.refund_no AND o.event_type = 'RefundSucceeded'
                  )

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SUCCESS_TRANSACTION_UNEXPECTED'
                FROM refund_order r
                WHERE r.status <> 'SUCCESS'
                  AND EXISTS (
                    SELECT 1 FROM refund_transaction t
                    WHERE t.refund_id = r.id AND t.status = 'SUCCESS'
                  )

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SUCCESS_TRANSACTION_DUPLICATE'
                FROM refund_order r
                WHERE (
                    SELECT COUNT(*) FROM refund_transaction t
                    WHERE t.refund_id = r.id AND t.status = 'SUCCESS'
                  ) > 1

                UNION ALL

                SELECT 'REFUND', r.refund_no, 'REFUND_SOURCE_PAYMENT_MISMATCH'
                FROM refund_order r
                JOIN payment_order p ON p.id = r.payment_id
                WHERE p.status <> 'SUCCESS' OR p.payment_no <> r.payment_no
                   OR p.order_no <> r.order_no OR p.user_id <> r.user_id
                   OR p.amount <> r.amount
            ) findings
            ORDER BY domain, reference_no, issue_type
            LIMIT #{limit}
            """)
    List<ReconciliationFinding> selectFindings(@Param("limit") int limit);

    @Insert("""
            INSERT IGNORE INTO reconciliation_record
                (id, domain, reference_no, issue_type, status, occurrences,
                 first_detected_at, last_detected_at, resolved_at)
            VALUES
                (#{entity.id}, #{entity.domain}, #{entity.referenceNo}, #{entity.issueType},
                 #{entity.status}, #{entity.occurrences}, #{entity.firstDetectedAt},
                 #{entity.lastDetectedAt}, #{entity.resolvedAt})
            """)
    int insertIfAbsent(@Param("entity") ReconciliationRecordEntity entity);

    @Update("""
            UPDATE reconciliation_record
            SET status = 'OPEN', occurrences = occurrences + 1,
                last_detected_at = #{detectedAt}, resolved_at = NULL
            WHERE domain = #{domain} AND reference_no = #{referenceNo}
              AND issue_type = #{issueType}
            """)
    int touchOpen(
            @Param("domain") String domain,
            @Param("referenceNo") String referenceNo,
            @Param("issueType") String issueType,
            @Param("detectedAt") Instant detectedAt);

    @Update("""
            UPDATE reconciliation_record
            SET status = 'RESOLVED', resolved_at = #{resolvedAt}
            WHERE id = #{id} AND status = 'OPEN'
              AND occurrences = #{expectedOccurrences}
              AND last_detected_at = #{expectedLastDetectedAt}
            """)
    int markResolved(
            @Param("id") Long id,
            @Param("expectedOccurrences") int expectedOccurrences,
            @Param("expectedLastDetectedAt") Instant expectedLastDetectedAt,
            @Param("resolvedAt") Instant resolvedAt);

    @Select("""
            SELECT * FROM reconciliation_record
            WHERE status = #{status}
            ORDER BY last_detected_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ReconciliationRecordEntity> selectByStatus(
            @Param("status") String status,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM reconciliation_record WHERE status = 'OPEN'")
    long countOpen();
}
