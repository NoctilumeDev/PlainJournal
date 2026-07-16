package com.ecommerce.marketing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("marketing_rule_region")
public class RuleRegionEntity {
    @TableId
    private Long id;
    private Long ruleId;
    private String regionLevel;
    private String regionCode;
    private Instant createdAt;
}
