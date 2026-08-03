package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.platform.common.observability.BusinessProcessEntry;
import com.ecommerce.trade.infrastructure.persistence.entity.TradeOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface TradeOrderMapper extends BaseMapper<TradeOrderEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO trade_order
                (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
                 warehouse_code, warehouse_id, status, original_amount, discount_amount,
                 total_amount, marketing_lock_no, payment_no, fulfillment_no, payment_deadline,
                 order_source, source_reference, close_reason, recovery_attempts, next_recovery_at,
                 last_error, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.orderNo}, #{entity.userId}, #{entity.idempotencyKey},
                 #{entity.requestHash}, #{entity.reservationNo}, #{entity.warehouseCode},
                 #{entity.warehouseId}, #{entity.status}, #{entity.originalAmount},
                 #{entity.discountAmount}, #{entity.totalAmount}, #{entity.marketingLockNo},
                 #{entity.paymentNo}, #{entity.fulfillmentNo}, #{entity.paymentDeadline},
                 #{entity.orderSource}, #{entity.sourceReference}, #{entity.closeReason},
                 #{entity.recoveryAttempts}, #{entity.nextRecoveryAt}, #{entity.lastError},
                 #{entity.version}, #{entity.createdAt}, #{entity.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") TradeOrderEntity entity);

    @Select("SELECT * FROM trade_order WHERE user_id = #{userId} AND idempotency_key = #{key} FOR UPDATE")
    TradeOrderEntity selectByIdempotencyForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT * FROM trade_order WHERE user_id = #{userId} AND idempotency_key = #{key}")
    TradeOrderEntity selectByIdempotency(@Param("userId") Long userId, @Param("key") String key);

    @Select("""
            <script>
            SELECT * FROM trade_order
            WHERE user_id = #{userId}
            <if test="cursorCreatedAt != null">
              AND (
                created_at &lt; #{cursorCreatedAt}
                OR (created_at = #{cursorCreatedAt} AND id &lt; #{cursorId})
              )
            </if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<TradeOrderEntity> selectUserCursorPage(
            @Param("userId") Long userId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);

    @Select("SELECT * FROM trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrderEntity selectForUpdate(@Param("orderNo") String orderNo);

    @Select("""
            SELECT order_no FROM trade_order
            WHERE status IN ('PENDING_STOCK', 'PAYMENT_CONFIRMING', 'CANCELING')
              AND (next_recovery_at IS NULL OR next_recovery_at <= #{now})
              AND (recovery_claim_until IS NULL OR recovery_claim_until <= #{now})
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<String> selectRecoverableOrderNumbers(@Param("now") Instant now, @Param("limit") int limit);

    @Select("""
            SELECT order_no FROM trade_order
            WHERE status = 'PENDING_PAYMENT' AND payment_deadline <= #{now}
              AND (recovery_claim_until IS NULL OR recovery_claim_until <= #{now})
            ORDER BY payment_deadline
            LIMIT #{limit}
            """)
    List<String> selectTimedOutOrderNumbers(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE trade_order
            SET recovery_claim_owner = #{owner}, recovery_claim_until = #{claimUntil}
            WHERE order_no = #{orderNo}
              AND (
                (status IN ('PENDING_STOCK', 'PAYMENT_CONFIRMING', 'CANCELING')
                  AND (next_recovery_at IS NULL OR next_recovery_at <= #{now}))
                OR (status = 'PENDING_PAYMENT' AND payment_deadline <= #{now})
              )
              AND (recovery_claim_until IS NULL OR recovery_claim_until <= #{now})
            """)
    int claimRecovery(
            @Param("orderNo") String orderNo,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE trade_order
            SET recovery_claim_owner = NULL, recovery_claim_until = NULL
            WHERE order_no = #{orderNo} AND recovery_claim_owner = #{owner}
            """)
    int releaseRecoveryClaim(
            @Param("orderNo") String orderNo,
            @Param("owner") String owner);

    @Select("SELECT COUNT(*) FROM trade_order WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT MIN(updated_at) FROM trade_order WHERE status = #{status}")
    Instant selectOldestUpdatedAtByStatus(@Param("status") String status);

    @Select("""
            SELECT 'ORDER' AS domain, status, order_no AS reference_no,
                   NULL AS stage, last_error, updated_at AS last_updated_at
            FROM trade_order
            WHERE status IN (
                'PENDING_STOCK', 'PAYMENT_CONFIRMING', 'CANCELING', 'PAYMENT_EXCEPTION')
            ORDER BY updated_at, order_no
            LIMIT #{limit}
            """)
    List<BusinessProcessEntry> selectOldestActive(@Param("limit") int limit);
}
