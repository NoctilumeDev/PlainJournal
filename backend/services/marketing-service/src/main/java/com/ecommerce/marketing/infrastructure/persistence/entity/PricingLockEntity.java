package com.ecommerce.marketing.infrastructure.persistence.entity;

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
@TableName("pricing_lock")
public class PricingLockEntity {
    @TableId
    private Long id;
    private String lockNo;
    private String orderNo;
    private Long userId;
    private String requestHash;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private String status;
    private Instant lockedAt;
    private Instant releasedAt;
    private Instant redeemedAt;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
