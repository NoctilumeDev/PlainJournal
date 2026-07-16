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
@TableName("order_price_snapshot")
public class OrderPriceSnapshotEntity {
    @TableId
    private Long id;
    private Long orderId;
    private String marketingLockNo;
    private BigDecimal originalAmount;
    private BigDecimal couponDiscount;
    private BigDecimal redPacketDiscount;
    private BigDecimal subsidyDiscount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private String pricingVersion;
    private Instant createdAt;
}
