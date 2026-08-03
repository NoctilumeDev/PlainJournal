package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentExceptionRefundAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PaymentExceptionRefundAuditMapper
        extends BaseMapper<PaymentExceptionRefundAuditEntity> {

    @Insert("""
            INSERT INTO payment_exception_refund_audit
                (id, command_id, request_hash, payment_no, order_no, refund_no,
                 operator_id, reason, outcome, error_code, created_at)
            VALUES
                (#{entity.id}, #{entity.commandId}, #{entity.requestHash},
                 #{entity.paymentNo}, #{entity.orderNo}, #{entity.refundNo},
                 #{entity.operatorId}, #{entity.reason}, #{entity.outcome},
                 #{entity.errorCode}, #{entity.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(
            @Param("entity") PaymentExceptionRefundAuditEntity entity);

    @Select("""
            SELECT * FROM payment_exception_refund_audit
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    PaymentExceptionRefundAuditEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);

    @Select("""
            SELECT * FROM payment_exception_refund_audit
            WHERE payment_no = #{paymentNo}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<PaymentExceptionRefundAuditEntity> selectByPaymentNo(
            @Param("paymentNo") String paymentNo,
            @Param("limit") int limit);
}
