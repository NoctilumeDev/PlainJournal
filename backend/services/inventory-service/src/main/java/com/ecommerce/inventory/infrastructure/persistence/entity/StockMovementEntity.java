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
@TableName("stock_movement")
public class StockMovementEntity {
    @TableId
    private Long id;
    private String movementNo;
    private Long warehouseId;
    private Long skuId;
    private String reservationNo;
    private String movementType;
    private Long quantityDelta;
    private Long onHandAfter;
    private Long reservedAfter;
    private String reason;
    private Instant createdAt;
}
