package com.ecommerce.fulfillment.infrastructure.persistence.entity;

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
@TableName("return_item")
public class ReturnItemEntity {
    @TableId
    private Long id;
    private Long returnReceiptId;
    private Integer lineNo;
    private Long skuId;
    private Long quantity;
    private BigDecimal refundableAmount;
    private Instant createdAt;
}
