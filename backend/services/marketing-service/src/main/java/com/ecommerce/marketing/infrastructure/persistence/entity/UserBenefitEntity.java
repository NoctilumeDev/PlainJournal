package com.ecommerce.marketing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("user_benefit")
public class UserBenefitEntity {
    @TableId
    private Long id;
    private String benefitNo;
    private String grantKey;
    private Long ruleId;
    private Long userId;
    private String status;
    private String lockedOrderNo;
    private Instant lockedAt;
    private String redeemedOrderNo;
    private Instant redeemedAt;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
