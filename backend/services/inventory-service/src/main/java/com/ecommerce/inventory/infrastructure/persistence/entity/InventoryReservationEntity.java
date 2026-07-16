package com.ecommerce.inventory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("inventory_reservation")
public class InventoryReservationEntity {
    @TableId
    private Long id;
    private String reservationNo;
    private String orderNo;
    private String requestHash;
    private Long warehouseId;
    private String status;
    private Instant expiresAt;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
