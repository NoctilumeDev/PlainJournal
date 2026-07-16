package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

public interface InventoryBalanceMapper extends BaseMapper<InventoryBalanceEntity> {

    @Insert("""
            INSERT IGNORE INTO inventory_balance
                (id, warehouse_id, sku_id, on_hand, reserved, version, created_at, updated_at)
            VALUES
                (#{id}, #{warehouseId}, #{skuId}, 0, 0, 0, #{now}, #{now})
            """)
    int insertZeroIfAbsent(
            @Param("id") Long id,
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId,
            @Param("now") Instant now);

    @Update("""
            UPDATE inventory_balance
            SET on_hand = on_hand + #{quantityDelta},
                version = version + 1,
                updated_at = #{now}
            WHERE warehouse_id = #{warehouseId}
              AND sku_id = #{skuId}
              AND on_hand + #{quantityDelta} >= reserved
              AND on_hand + #{quantityDelta} >= 0
            """)
    int adjustOnHand(
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId,
            @Param("quantityDelta") long quantityDelta,
            @Param("now") Instant now);

    @Update("""
            UPDATE inventory_balance
            SET reserved = reserved + #{quantity},
                version = version + 1,
                updated_at = #{now}
            WHERE warehouse_id = #{warehouseId}
              AND sku_id = #{skuId}
              AND on_hand - reserved >= #{quantity}
            """)
    int reserve(
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId,
            @Param("quantity") long quantity,
            @Param("now") Instant now);

    @Update("""
            UPDATE inventory_balance
            SET reserved = reserved - #{quantity},
                version = version + 1,
                updated_at = #{now}
            WHERE warehouse_id = #{warehouseId}
              AND sku_id = #{skuId}
              AND reserved >= #{quantity}
            """)
    int release(
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId,
            @Param("quantity") long quantity,
            @Param("now") Instant now);

    @Update("""
            UPDATE inventory_balance
            SET on_hand = on_hand - #{quantity},
                reserved = reserved - #{quantity},
                version = version + 1,
                updated_at = #{now}
            WHERE warehouse_id = #{warehouseId}
              AND sku_id = #{skuId}
              AND on_hand >= #{quantity}
              AND reserved >= #{quantity}
            """)
    int confirm(
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId,
            @Param("quantity") long quantity,
            @Param("now") Instant now);

    @Select("""
            SELECT * FROM inventory_balance
            WHERE warehouse_id = #{warehouseId} AND sku_id = #{skuId}
            FOR UPDATE
            """)
    InventoryBalanceEntity selectForUpdate(
            @Param("warehouseId") Long warehouseId,
            @Param("skuId") Long skuId);
}
