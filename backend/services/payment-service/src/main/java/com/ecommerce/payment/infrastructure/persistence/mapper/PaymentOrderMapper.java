package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {

    @Insert("""
            INSERT IGNORE INTO payment_order
                (id, payment_no, order_no, user_id, reservation_no, idempotency_key,
                 request_hash, channel, status, amount, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.paymentNo}, #{entity.orderNo}, #{entity.userId},
                 #{entity.reservationNo}, #{entity.idempotencyKey}, #{entity.requestHash},
                 #{entity.channel}, #{entity.status}, #{entity.amount}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") PaymentOrderEntity entity);

    @Select("SELECT * FROM payment_order WHERE user_id = #{userId} AND idempotency_key = #{key} FOR UPDATE")
    PaymentOrderEntity selectByIdempotencyForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT * FROM payment_order WHERE order_no = #{orderNo} FOR UPDATE")
    PaymentOrderEntity selectByOrderForUpdate(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM payment_order WHERE payment_no = #{paymentNo} FOR UPDATE")
    PaymentOrderEntity selectByPaymentNoForUpdate(@Param("paymentNo") String paymentNo);
}
