package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.inventory.infrastructure.reconciliation.ReconciliationFinding;
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
                SELECT 'BALANCE' AS domain,
                       CONCAT(b.warehouse_id, ':', b.sku_id) AS reference_no,
                       'BALANCE_RESERVED_MISMATCH' AS issue_type
                FROM inventory_balance b
                WHERE b.reserved <> COALESCE((
                    SELECT SUM(i.quantity)
                    FROM inventory_reservation r
                    JOIN inventory_reservation_item i ON i.reservation_id = r.id
                    WHERE r.status = 'RESERVED'
                      AND r.warehouse_id = b.warehouse_id
                      AND i.sku_id = b.sku_id
                ), 0)

                UNION ALL

                SELECT 'ADJUSTMENT', a.movement_no, 'ADJUSTMENT_STUCK_PENDING'
                FROM stock_adjustment a
                WHERE a.status = 'PENDING'

                UNION ALL

                SELECT 'ADJUSTMENT', a.movement_no, 'ADJUSTMENT_MOVEMENT_MISMATCH'
                FROM stock_adjustment a
                WHERE a.status = 'APPLIED'
                  AND NOT EXISTS (
                    SELECT 1 FROM stock_movement m
                    WHERE m.movement_no = a.movement_no
                      AND m.warehouse_id = a.warehouse_id
                      AND m.sku_id = a.sku_id
                      AND m.reservation_no IS NULL
                      AND m.movement_type = 'ADJUSTMENT'
                      AND m.quantity_delta = a.quantity_delta
                  )

                UNION ALL

                SELECT 'RESERVATION', r.reservation_no, 'RESERVATION_STUCK_PENDING'
                FROM inventory_reservation r
                WHERE r.status = 'PENDING'

                UNION ALL

                SELECT 'RESERVATION', r.reservation_no, 'RESERVATION_BALANCE_MISSING'
                FROM inventory_reservation r
                WHERE r.status = 'RESERVED'
                  AND EXISTS (
                    SELECT 1 FROM inventory_reservation_item i
                    WHERE i.reservation_id = r.id
                      AND NOT EXISTS (
                        SELECT 1 FROM inventory_balance b
                        WHERE b.warehouse_id = r.warehouse_id AND b.sku_id = i.sku_id
                      )
                  )

                UNION ALL

                SELECT 'RESERVATION', r.reservation_no, 'RESERVATION_MOVEMENT_MISMATCH'
                FROM inventory_reservation r
                WHERE r.status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED')
                  AND EXISTS (
                    SELECT 1 FROM inventory_reservation_item i
                    WHERE i.reservation_id = r.id
                      AND (
                        NOT EXISTS (
                          SELECT 1 FROM stock_movement m
                          WHERE m.movement_no = CONCAT(r.reservation_no, ':RESERVE:', i.sku_id)
                            AND m.warehouse_id = r.warehouse_id
                            AND m.sku_id = i.sku_id
                            AND m.reservation_no = r.reservation_no
                            AND m.movement_type = 'RESERVE'
                            AND m.quantity_delta = i.quantity
                        )
                        OR (r.status = 'CONFIRMED' AND NOT EXISTS (
                          SELECT 1 FROM stock_movement m
                          WHERE m.movement_no = CONCAT(r.reservation_no, ':CONFIRM:', i.sku_id)
                            AND m.warehouse_id = r.warehouse_id
                            AND m.sku_id = i.sku_id
                            AND m.reservation_no = r.reservation_no
                            AND m.movement_type = 'CONFIRM'
                            AND m.quantity_delta = -i.quantity
                        ))
                        OR (r.status = 'RELEASED' AND NOT EXISTS (
                          SELECT 1 FROM stock_movement m
                          WHERE m.movement_no = CONCAT(r.reservation_no, ':RELEASE:', i.sku_id)
                            AND m.warehouse_id = r.warehouse_id
                            AND m.sku_id = i.sku_id
                            AND m.reservation_no = r.reservation_no
                            AND m.movement_type = 'RELEASE'
                            AND m.quantity_delta = -i.quantity
                        ))
                        OR (r.status = 'EXPIRED' AND NOT EXISTS (
                          SELECT 1 FROM stock_movement m
                          WHERE m.movement_no = CONCAT(r.reservation_no, ':EXPIRE:', i.sku_id)
                            AND m.warehouse_id = r.warehouse_id
                            AND m.sku_id = i.sku_id
                            AND m.reservation_no = r.reservation_no
                            AND m.movement_type = 'EXPIRE'
                            AND m.quantity_delta = -i.quantity
                        ))
                      )
                  )

                UNION ALL

                SELECT 'RESERVATION', r.reservation_no, 'RESERVATION_EVENT_MISSING'
                FROM inventory_reservation r
                WHERE (r.status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED')
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.reservation_no
                      AND o.event_type = 'InventoryReserved'
                  ))
                   OR (r.status = 'REJECTED' AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.reservation_no
                      AND o.event_type = 'InventoryReservationRejected'
                  ))
                   OR (r.status = 'CONFIRMED' AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.reservation_no
                      AND o.event_type = 'InventoryReservationConfirmed'
                  ))
                   OR (r.status = 'RELEASED' AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.reservation_no
                      AND o.event_type = 'InventoryReservationReleased'
                  ))
                   OR (r.status = 'EXPIRED' AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = r.reservation_no
                      AND o.event_type = 'InventoryReservationExpired'
                  ))

                UNION ALL

                SELECT 'RETURN', ir.after_sale_no, 'RETURN_STATUS_INCOMPLETE'
                FROM inventory_return ir
                WHERE (ir.status = 'STOCKED' AND ir.stocked_at IS NULL)
                   OR ir.status <> 'STOCKED'

                UNION ALL

                SELECT 'RETURN', ir.after_sale_no, 'RETURN_SOURCE_RESERVATION_MISMATCH'
                FROM inventory_return ir
                LEFT JOIN inventory_reservation r ON r.reservation_no = ir.reservation_no
                WHERE r.id IS NULL OR r.status <> 'CONFIRMED'
                   OR r.order_no <> ir.order_no OR r.warehouse_id <> ir.warehouse_id

                UNION ALL

                SELECT 'RETURN', ir.after_sale_no, 'RETURN_MOVEMENT_MISMATCH'
                FROM inventory_return ir
                JOIN inventory_reservation r ON r.reservation_no = ir.reservation_no
                WHERE ir.status = 'STOCKED'
                  AND EXISTS (
                    SELECT 1 FROM inventory_reservation_item i
                    WHERE i.reservation_id = r.id
                      AND NOT EXISTS (
                        SELECT 1 FROM stock_movement m
                        WHERE m.movement_no = CONCAT(ir.after_sale_no, ':RETURN:', i.sku_id)
                          AND m.warehouse_id = ir.warehouse_id
                          AND m.sku_id = i.sku_id
                          AND m.reservation_no = ir.reservation_no
                          AND m.movement_type = 'RETURN'
                          AND m.quantity_delta = i.quantity
                      )
                  )

                UNION ALL

                SELECT 'RETURN', ir.after_sale_no, 'RETURN_EVENT_MISSING'
                FROM inventory_return ir
                WHERE ir.status = 'STOCKED'
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_event o
                    WHERE o.aggregate_id = ir.after_sale_no AND o.event_type = 'ReturnStocked'
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
