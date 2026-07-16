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
@TableName("inventory_balance")
public class InventoryBalanceEntity {
    @TableId
    private Long id;
    private Long warehouseId;
    private Long skuId;
    private Long onHand;
    private Long reserved;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
