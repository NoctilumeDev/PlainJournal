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
@TableName("after_sale_item")
public class AfterSaleItemEntity {
    @TableId
    private Long id;
    private Long afterSaleId;
    private Long orderItemId;
    private Integer lineNo;
    private Long skuId;
    private String productTitle;
    private String skuName;
    private Long quantity;
    private BigDecimal lineAmount;
    private BigDecimal discountAmount;
    private BigDecimal refundableAmount;
    private Instant createdAt;
}
