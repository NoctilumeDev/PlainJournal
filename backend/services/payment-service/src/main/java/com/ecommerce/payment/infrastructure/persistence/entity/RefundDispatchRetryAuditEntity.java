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
@TableName("refund_dispatch_retry_audit")
public class RefundDispatchRetryAuditEntity {
    @TableId
    private Long id;
    private String commandId;
    private String requestHash;
    private String refundNo;
    private String operatorId;
    private String reason;
    private String outcome;
    private String errorCode;
    private String beforeRefundStatus;
    private String beforeRequestStatus;
    private Integer beforeRequestAttempts;
    private String beforeLastError;
    private String afterRefundStatus;
    private String afterRequestStatus;
    private Integer afterRequestAttempts;
    private Instant createdAt;
}
