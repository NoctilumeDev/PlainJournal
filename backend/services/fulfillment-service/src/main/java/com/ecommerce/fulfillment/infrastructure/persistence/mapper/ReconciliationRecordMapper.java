package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.fulfillment.infrastructure.reconciliation.ReconciliationFinding;
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
                SELECT 'FULFILLMENT' AS domain, f.fulfillment_no AS reference_no,
                       'FULFILLMENT_HISTORY_STATE_MISMATCH' AS issue_type
                FROM fulfillment_order f
                WHERE NOT EXISTS (
                    SELECT 1 FROM fulfillment_status_history h
                    WHERE h.id = (
                        SELECT h2.id FROM fulfillment_status_history h2
                        WHERE h2.fulfillment_id = f.id
                        ORDER BY h2.created_at DESC, h2.id DESC LIMIT 1
                    ) AND h.to_status = f.status
                )

                UNION ALL

                SELECT 'FULFILLMENT', f.fulfillment_no, 'FULFILLMENT_TIMESTAMP_MISMATCH'
                FROM fulfillment_order f
                WHERE (EXISTS (
                           SELECT 1 FROM fulfillment_status_history h
                           WHERE h.fulfillment_id = f.id AND h.to_status = 'PICKING')
                           AND f.picked_at IS NULL)
                   OR (EXISTS (
                           SELECT 1 FROM fulfillment_status_history h
                           WHERE h.fulfillment_id = f.id AND h.to_status = 'PACKED')
                           AND f.packed_at IS NULL)
                   OR (EXISTS (
                           SELECT 1 FROM fulfillment_status_history h
                           WHERE h.fulfillment_id = f.id AND h.to_status = 'SHIPPED')
                           AND (f.shipped_at IS NULL OR f.carrier IS NULL OR f.tracking_no IS NULL))
                   OR (EXISTS (
                           SELECT 1 FROM fulfillment_status_history h
                           WHERE h.fulfillment_id = f.id AND h.to_status = 'SIGNED')
                           AND f.signed_at IS NULL)

                UNION ALL

                SELECT 'FULFILLMENT', f.fulfillment_no, 'FULFILLMENT_STATE_EVENT_MISSING'
                FROM fulfillment_order f
                WHERE NOT EXISTS (
                          SELECT 1 FROM outbox_event e WHERE e.aggregate_id = f.fulfillment_no
                            AND e.event_type = 'FulfillmentCreated')
                   OR EXISTS (
                          SELECT 1
                          FROM fulfillment_status_history h
                          WHERE h.fulfillment_id = f.id
                            AND h.to_status IN ('SHIPPED', 'SIGNED', 'EXCEPTION')
                            AND NOT EXISTS (
                                SELECT 1
                                FROM outbox_event e
                                WHERE e.aggregate_id = f.fulfillment_no
                                  AND e.event_type = CASE h.to_status
                                      WHEN 'SHIPPED' THEN 'ShipmentDispatched'
                                      WHEN 'SIGNED' THEN 'ShipmentSigned'
                                      WHEN 'EXCEPTION' THEN 'FulfillmentExceptionDetected'
                                  END
                            )
                      )

                UNION ALL

                SELECT 'FULFILLMENT', f.fulfillment_no, 'FULFILLMENT_SIGNED_TRACE_MISSING'
                FROM fulfillment_order f
                WHERE f.status = 'SIGNED'
                  AND NOT EXISTS (
                    SELECT 1 FROM logistics_trace t
                    WHERE t.fulfillment_id = f.id AND t.node_type = 'SIGNED'
                      AND t.occurred_at = f.signed_at
                  )

                UNION ALL

                SELECT 'RETURN', r.return_receipt_no, 'RETURN_HISTORY_STATE_MISMATCH'
                FROM return_receipt r
                WHERE NOT EXISTS (
                    SELECT 1 FROM return_status_history h
                    WHERE h.id = (
                        SELECT h2.id FROM return_status_history h2
                        WHERE h2.return_receipt_id = r.id
                        ORDER BY h2.created_at DESC, h2.id DESC LIMIT 1
                    ) AND h.to_status = r.status
                )

                UNION ALL

                SELECT 'RETURN', r.return_receipt_no, 'RETURN_TIMESTAMP_MISMATCH'
                FROM return_receipt r
                WHERE (EXISTS (
                           SELECT 1 FROM return_status_history h
                           WHERE h.return_receipt_id = r.id AND h.to_status = 'RETURNING')
                           AND (r.shipped_at IS NULL OR r.carrier IS NULL OR r.tracking_no IS NULL))
                   OR (EXISTS (
                           SELECT 1 FROM return_status_history h
                           WHERE h.return_receipt_id = r.id AND h.to_status = 'RECEIVED')
                           AND r.received_at IS NULL)
                   OR (EXISTS (
                           SELECT 1 FROM return_status_history h
                           WHERE h.return_receipt_id = r.id AND h.to_status = 'INSPECTED')
                           AND r.inspected_at IS NULL)

                UNION ALL

                SELECT 'RETURN', r.return_receipt_no, 'RETURN_ITEM_TOTAL_MISMATCH'
                FROM return_receipt r
                LEFT JOIN (
                    SELECT return_receipt_id, SUM(refundable_amount) AS refundable_amount
                    FROM return_item GROUP BY return_receipt_id
                ) i ON i.return_receipt_id = r.id
                WHERE COALESCE(i.refundable_amount, 0) <> r.refund_amount

                UNION ALL

                SELECT 'RETURN', r.return_receipt_no, 'RETURN_STATE_EVENT_MISSING'
                FROM return_receipt r
                WHERE NOT EXISTS (
                          SELECT 1 FROM outbox_event e WHERE e.aggregate_id = r.return_receipt_no
                            AND e.event_type = 'ReturnReceiptCreated')
                   OR EXISTS (
                          SELECT 1
                          FROM return_status_history h
                          WHERE h.return_receipt_id = r.id
                            AND h.to_status IN ('RETURNING', 'RECEIVED', 'INSPECTED')
                            AND NOT EXISTS (
                                SELECT 1
                                FROM outbox_event e
                                WHERE e.aggregate_id = r.return_receipt_no
                                  AND e.event_type = CASE h.to_status
                                      WHEN 'RETURNING' THEN 'ReturnShipmentSubmitted'
                                      WHEN 'RECEIVED' THEN 'ReturnReceived'
                                      WHEN 'INSPECTED' THEN 'ReturnInspected'
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
