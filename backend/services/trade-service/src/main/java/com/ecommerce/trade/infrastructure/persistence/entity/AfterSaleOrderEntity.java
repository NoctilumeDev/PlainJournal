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
@TableName("after_sale_order")
public class AfterSaleOrderEntity {
    @TableId
    private Long id;
    private String afterSaleNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private String afterSaleType;
    private String status;
    private String idempotencyKey;
    private String requestHash;
    private String reason;
    private String reviewReason;
    private BigDecimal refundAmount;
    private Long warehouseId;
    private String reservationNo;
    private String returnReceiptNo;
    private String refundNo;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant approvedAt;
    private Instant completedAt;
}
