package com.ecommerce.catalog.infrastructure.persistence.entity;

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
@TableName("product_sku")
public class ProductSkuEntity {
    @TableId
    private Long id;
    private Long spuId;
    private String skuCode;
    private String name;
    private String specJson;
    private BigDecimal salePrice;
    private BigDecimal marketPrice;
    private String status;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
