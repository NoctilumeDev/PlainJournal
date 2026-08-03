package com.ecommerce.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("flash_sale_order_request")
public class FlashSaleOrderRequestEntity {
    @TableId
    private Long id;
    private String requestToken;
    private String admissionEventId;
    private String requestHash;
    private String activityNo;
    private Long userId;
    private Long addressId;
    private Long productId;
    private Long skuId;
    private BigDecimal salePrice;
    private String status;
    private String orderNo;
    private String failureCode;
    private Integer attempts;
    private Instant nextAttemptAt;
    private String recoveryClaimOwner;
    private Instant recoveryClaimUntil;
    private String lastError;
    private Integer version;
    private Instant acceptedAt;
    private Instant activityEndsAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
