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
@TableName("order_discount_allocation")
public class OrderDiscountAllocationEntity {
    @TableId
    private Long id;
    private Long orderId;
    private Long orderItemId;
    private Integer lineNo;
    private Long skuId;
    private String benefitNo;
    private String ruleCode;
    private String benefitType;
    private BigDecimal discountAmount;
    private Instant createdAt;
}
