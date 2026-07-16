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
@TableName("order_item")
public class OrderItemEntity {
    @TableId
    private Long id;
    private Long orderId;
    private Integer lineNo;
    private Long productId;
    private Long skuId;
    private String productTitle;
    private String skuCode;
    private String skuName;
    private String specJson;
    private String imageObjectKey;
    private BigDecimal unitPrice;
    private Long quantity;
    private BigDecimal lineAmount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private Instant createdAt;
}
