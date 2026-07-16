package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.TradeOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

public interface TradeOrderMapper extends BaseMapper<TradeOrderEntity> {

    @Insert("""
            INSERT IGNORE INTO trade_order
                (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
                 warehouse_code, warehouse_id, status, original_amount, discount_amount,
                 total_amount, marketing_lock_no, payment_deadline,
                 close_reason, recovery_attempts, next_recovery_at, last_error, version,
                 created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.orderNo}, #{entity.userId}, #{entity.idempotencyKey},
                 #{entity.requestHash}, #{entity.reservationNo}, #{entity.warehouseCode},
                 #{entity.warehouseId}, #{entity.status}, #{entity.originalAmount},
                 #{entity.discountAmount}, #{entity.totalAmount}, #{entity.marketingLockNo},
                 #{entity.paymentDeadline}, #{entity.closeReason}, #{entity.recoveryAttempts},
                 #{entity.nextRecoveryAt}, #{entity.lastError}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") TradeOrderEntity entity);

    @Select("SELECT * FROM trade_order WHERE user_id = #{userId} AND idempotency_key = #{key} FOR UPDATE")
    TradeOrderEntity selectByIdempotencyForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT * FROM trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrderEntity selectForUpdate(@Param("orderNo") String orderNo);

    @Select("""
            SELECT order_no FROM trade_order
            WHERE status IN ('PENDING_STOCK', 'CANCELING')
              AND (next_recovery_at IS NULL OR next_recovery_at <= #{now})
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<String> selectRecoverableOrderNumbers(@Param("now") Instant now, @Param("limit") int limit);

    @Select("""
            SELECT order_no FROM trade_order
            WHERE status = 'PENDING_PAYMENT' AND payment_deadline <= #{now}
            ORDER BY payment_deadline
            LIMIT #{limit}
            """)
    List<String> selectTimedOutOrderNumbers(@Param("now") Instant now, @Param("limit") int limit);
}
