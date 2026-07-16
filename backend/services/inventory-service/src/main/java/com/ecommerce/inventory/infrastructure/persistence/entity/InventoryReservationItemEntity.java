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
@TableName("inventory_reservation_item")
public class InventoryReservationItemEntity {
    @TableId
    private Long id;
    private Long reservationId;
    private Long skuId;
    private Long quantity;
    private Instant createdAt;
}
