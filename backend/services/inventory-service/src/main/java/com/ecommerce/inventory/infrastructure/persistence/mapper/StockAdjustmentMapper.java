package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.StockAdjustmentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface StockAdjustmentMapper extends BaseMapper<StockAdjustmentEntity> {

    @Insert("""
            INSERT INTO stock_adjustment
                (id, movement_no, request_hash, warehouse_id, sku_id, quantity_delta,
                 reason, status, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.movementNo}, #{entity.requestHash}, #{entity.warehouseId},
                 #{entity.skuId}, #{entity.quantityDelta}, #{entity.reason}, #{entity.status},
                 #{entity.createdAt}, #{entity.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") StockAdjustmentEntity entity);

    @Select("SELECT * FROM stock_adjustment WHERE movement_no = #{movementNo} FOR UPDATE")
    StockAdjustmentEntity selectForUpdate(@Param("movementNo") String movementNo);
}
