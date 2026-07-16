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
@TableName("inventory_return")
public class InventoryReturnEntity {
    @TableId
    private Long id;
    private String afterSaleNo;
    private String returnReceiptNo;
    private String orderNo;
    private Long userId;
    private Long warehouseId;
    private String reservationNo;
    private String requestHash;
    private String status;
    private Instant createdAt;
    private Instant stockedAt;
}
