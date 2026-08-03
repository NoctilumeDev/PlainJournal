package com.ecommerce.marketing.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("flash_sale_admission")
public class FlashSaleAdmissionEntity {
    @TableId
    private Long id;
    private String requestToken;
    private String activityNo;
    private Long userId;
    private Long addressId;
    private String requestHash;
    private String status;
    private Integer remainingAdmissions;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String orderNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String failureCode;
    @Version
    private Integer version;
    private Instant acceptedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
