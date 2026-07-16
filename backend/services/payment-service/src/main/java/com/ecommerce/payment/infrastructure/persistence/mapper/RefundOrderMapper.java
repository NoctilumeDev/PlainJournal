package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {

    @Insert("""
            INSERT IGNORE INTO refund_order
                (id, refund_no, after_sale_no, order_no, payment_id, payment_no, user_id,
                 request_hash, channel, status, amount, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.refundNo}, #{entity.afterSaleNo}, #{entity.orderNo},
                 #{entity.paymentId}, #{entity.paymentNo}, #{entity.userId}, #{entity.requestHash},
                 #{entity.channel}, #{entity.status}, #{entity.amount}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") RefundOrderEntity entity);

    @Select("SELECT * FROM refund_order WHERE after_sale_no = #{afterSaleNo} FOR UPDATE")
    RefundOrderEntity selectByAfterSaleNoForUpdate(@Param("afterSaleNo") String afterSaleNo);

    @Select("SELECT * FROM refund_order WHERE refund_no = #{refundNo} FOR UPDATE")
    RefundOrderEntity selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    @Select("SELECT * FROM refund_order WHERE payment_id = #{paymentId} FOR UPDATE")
    RefundOrderEntity selectByPaymentIdForUpdate(@Param("paymentId") Long paymentId);
}
