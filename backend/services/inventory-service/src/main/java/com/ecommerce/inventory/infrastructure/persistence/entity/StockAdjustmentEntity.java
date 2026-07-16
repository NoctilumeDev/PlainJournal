package com.ecommerce.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("stock_adjustment")
public class StockAdjustmentEntity {
    @TableId
    private Long id;
    private String movementNo;
    private String requestHash;
    private Long warehouseId;
    private Long skuId;
    private Long quantityDelta;
    private String reason;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
