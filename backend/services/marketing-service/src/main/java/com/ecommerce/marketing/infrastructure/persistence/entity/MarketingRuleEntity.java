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
@TableName("marketing_rule")
public class MarketingRuleEntity {
    @TableId
    private Long id;
    private String ruleCode;
    private String name;
    private String benefitType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer stackOrder;
    private String status;
    private Instant validFrom;
    private Instant validUntil;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
