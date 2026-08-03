package com.ecommerce.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("trade_order")
public class TradeOrderEntity {
    @TableId
    private Long id;
    private String orderNo;
    private Long userId;
    private String idempotencyKey;
    private String requestHash;
    private String reservationNo;
    private String warehouseCode;
    private Long warehouseId;
    private String orderSource;
    private String sourceReference;
    private String status;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String marketingLockNo;
    private String paymentNo;
    private String exceptionRefundNo;
    private String fulfillmentNo;
    private Instant paymentDeadline;
    private String closeReason;
    private Integer recoveryAttempts;
    private Instant nextRecoveryAt;
    private String recoveryClaimOwner;
    private Instant recoveryClaimUntil;
    private String lastError;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
