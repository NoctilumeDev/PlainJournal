package com.ecommerce.marketing.infrastructure.persistence.entity;

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
@TableName("pricing_lock_benefit")
public class PricingLockBenefitEntity {
    @TableId
    private Long id;
    private Long lockId;
    private Long userBenefitId;
    private String benefitNo;
    private String ruleCode;
    private String benefitType;
    private BigDecimal discountAmount;
    private Instant createdAt;
}
