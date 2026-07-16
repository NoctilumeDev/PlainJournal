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
@TableName("warehouse")
public class WarehouseEntity {
    @TableId
    private Long id;
    private String code;
    private String name;
    private String status;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
