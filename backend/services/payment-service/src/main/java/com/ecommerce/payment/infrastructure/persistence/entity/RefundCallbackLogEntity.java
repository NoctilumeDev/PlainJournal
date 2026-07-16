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
@TableName("refund_callback_log")
public class RefundCallbackLogEntity {
    @TableId
    private Long id;
    private String channel;
    private String externalEventId;
    private String refundNo;
    private String requestHash;
    private Boolean signatureValid;
    private String processingStatus;
    private String rawPayload;
    private String errorMessage;
    private Instant receivedAt;
    private Instant processedAt;
}
