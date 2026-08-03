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
@TableName("callback_security_audit")
public class CallbackSecurityAuditEntity {
    @TableId
    private Long id;
    private String callbackType;
    private String channel;
    private String claimedExternalEventId;
    private String referenceNo;
    private String requestHash;
    private Boolean signatureValid;
    private String errorCode;
    private String rawPayload;
    private Instant receivedAt;
}
