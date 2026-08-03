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
@TableName("flash_sale_activity")
public class FlashSaleActivityEntity {
    @TableId
    private Long id;
    private String activityNo;
    private String name;
    private Long productId;
    private Long skuId;
    private BigDecimal salePrice;
    private Integer admissionLimit;
    private String status;
    private Instant startsAt;
    private Instant endsAt;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
