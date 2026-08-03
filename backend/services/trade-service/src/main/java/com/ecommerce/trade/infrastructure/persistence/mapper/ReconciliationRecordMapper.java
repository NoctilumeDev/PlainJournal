package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.trade.infrastructure.reconciliation.ReconciliationFinding;
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
                SELECT 'ORDER' AS domain, o.order_no AS reference_no,
                       'ORDER_HISTORY_STATE_MISMATCH' AS issue_type
                FROM trade_order o
                WHERE NOT EXISTS (
                    SELECT 1 FROM order_status_history h
                    WHERE h.id = (
                        SELECT h2.id FROM order_status_history h2
                        WHERE h2.order_id = o.id
                        ORDER BY h2.created_at DESC, h2.id DESC LIMIT 1
                    ) AND h.to_status = o.status
                )

                UNION ALL

                SELECT 'ORDER', o.order_no, 'ORDER_PRICE_SNAPSHOT_MISMATCH'
                FROM trade_order o
                WHERE o.marketing_lock_no IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM order_price_snapshot p
                    WHERE p.order_id = o.id
                      AND p.marketing_lock_no = o.marketing_lock_no
                      AND p.original_amount = o.original_amount
                      AND p.discount_amount = o.discount_amount
                      AND p.payable_amount = o.total_amount
                  )

                UNION ALL

                SELECT 'ORDER', o.order_no, 'ORDER_ITEM_TOTAL_MISMATCH'
                FROM trade_order o
                LEFT JOIN (
                    SELECT order_id, SUM(line_amount) AS original_amount,
                           SUM(discount_amount) AS discount_amount,
                           SUM(payable_amount) AS payable_amount
                    FROM order_item GROUP BY order_id
                ) i ON i.order_id = o.id
                WHERE COALESCE(i.original_amount, 0) <> o.original_amount
                   OR COALESCE(i.discount_amount, 0) <> o.discount_amount
                   OR COALESCE(i.payable_amount, 0) <> o.total_amount

                UNION ALL

                SELECT 'ORDER', o.order_no, 'ORDER_STATE_EVENT_MISSING'
                FROM trade_order o
                WHERE NOT EXISTS (
                          SELECT 1 FROM outbox_event e WHERE e.aggregate_id = o.order_no
                            AND e.event_type = 'OrderCreated')
                   OR EXISTS (
                          SELECT 1
                          FROM order_status_history h
                          WHERE h.order_id = o.id
                            AND h.to_status IN (
                                'PENDING_PAYMENT', 'PAYMENT_CONFIRMING', 'CANCELING',
                                'CANCELED', 'CLOSED',
                                'PAID', 'FULFILLING', 'SHIPPED', 'COMPLETED',
                                'PAYMENT_EXCEPTION')
                            AND NOT EXISTS (
                                SELECT 1
                                FROM outbox_event e
                                WHERE e.aggregate_id = o.order_no
                                  AND e.event_type = CASE h.to_status
                                      WHEN 'PENDING_PAYMENT' THEN 'OrderAwaitingPayment'
                                      WHEN 'PAYMENT_CONFIRMING'
                                          THEN 'PaymentInventoryConfirmationRequested'
                                      WHEN 'CANCELING' THEN 'OrderCancellationRequested'
                                      WHEN 'CANCELED' THEN 'OrderCanceled'
                                      WHEN 'CLOSED' THEN 'OrderClosed'
                                      WHEN 'PAID' THEN 'OrderPaid'
                                      WHEN 'FULFILLING' THEN 'OrderFulfilling'
                                      WHEN 'SHIPPED' THEN 'OrderShipped'
                                      WHEN 'COMPLETED' THEN 'OrderCompleted'
                                      WHEN 'PAYMENT_EXCEPTION' THEN 'PaymentReviewRequired'
                                  END
                            )
                      )

                UNION ALL

                SELECT 'AFTER_SALE', a.after_sale_no, 'AFTER_SALE_HISTORY_STATE_MISMATCH'
                FROM after_sale_order a
                WHERE NOT EXISTS (
                    SELECT 1 FROM after_sale_history h
                    WHERE h.id = (
                        SELECT h2.id FROM after_sale_history h2
                        WHERE h2.after_sale_id = a.id
                        ORDER BY h2.created_at DESC, h2.id DESC LIMIT 1
                    ) AND h.to_status = a.status
                )

                UNION ALL

                SELECT 'AFTER_SALE', a.after_sale_no, 'AFTER_SALE_COMPLETION_INCOMPLETE'
                FROM after_sale_order a
                WHERE a.status = 'COMPLETED'
                  AND (a.refund_no IS NULL OR a.completed_at IS NULL)

                UNION ALL

                SELECT 'AFTER_SALE', a.after_sale_no, 'AFTER_SALE_ITEM_TOTAL_MISMATCH'
                FROM after_sale_order a
                LEFT JOIN (
                    SELECT after_sale_id, SUM(refundable_amount) AS refundable_amount
                    FROM after_sale_item GROUP BY after_sale_id
                ) i ON i.after_sale_id = a.id
                WHERE COALESCE(i.refundable_amount, 0) <> a.refund_amount

                UNION ALL

                SELECT 'AFTER_SALE', a.after_sale_no, 'AFTER_SALE_STATE_EVENT_MISSING'
                FROM after_sale_order a
                WHERE NOT EXISTS (
                          SELECT 1 FROM outbox_event e WHERE e.aggregate_id = a.after_sale_no
                            AND e.event_type = 'AfterSaleApplied')
                   OR EXISTS (
                          SELECT 1
                          FROM after_sale_history h
                          WHERE h.after_sale_id = a.id
                            AND h.to_status IN (
                                'WAIT_RETURN', 'RETURNING', 'RECEIVED', 'REFUNDING',
                                'REFUND_FAILED', 'COMPLETED', 'REJECTED', 'CANCELED')
                            AND NOT EXISTS (
                                SELECT 1
                                FROM outbox_event e
                                WHERE e.aggregate_id = a.after_sale_no
                                  AND e.event_type = CASE h.to_status
                                      WHEN 'WAIT_RETURN' THEN 'AfterSaleApproved'
                                      WHEN 'RETURNING' THEN 'AfterSaleReturning'
                                      WHEN 'RECEIVED' THEN 'AfterSaleReturnReceived'
                                      WHEN 'REFUNDING' THEN 'RefundRequested'
                                      WHEN 'REFUND_FAILED' THEN 'AfterSaleRefundFailed'
                                      WHEN 'COMPLETED' THEN 'AfterSaleCompleted'
                                      WHEN 'REJECTED' THEN 'AfterSaleRejected'
                                      WHEN 'CANCELED' THEN 'AfterSaleCanceled'
                                  END
                            )
                      )
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
    int touchOpen(@Param("domain") String domain, @Param("referenceNo") String referenceNo,
                  @Param("issueType") String issueType, @Param("detectedAt") Instant detectedAt);

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
            @Param("status") String status, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM reconciliation_record WHERE status = 'OPEN'")
    long countOpen();
}
