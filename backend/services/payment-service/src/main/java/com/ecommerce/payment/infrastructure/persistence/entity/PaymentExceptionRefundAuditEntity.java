package com.ecommerce.payment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("payment_exception_refund_audit")
public class PaymentExceptionRefundAuditEntity {
    @TableId
    private Long id;
    private String commandId;
    private String requestHash;
    private String paymentNo;
    private String orderNo;
    private String refundNo;
    private String operatorId;
    private String reason;
    private String outcome;
    private String errorCode;
    private Instant createdAt;
}
