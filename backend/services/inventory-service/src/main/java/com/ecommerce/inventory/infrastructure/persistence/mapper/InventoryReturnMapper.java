package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReturnEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InventoryReturnMapper extends BaseMapper<InventoryReturnEntity> {

    @Insert("""
            INSERT IGNORE INTO inventory_return
                (id, after_sale_no, return_receipt_no, order_no, user_id, warehouse_id,
                 reservation_no, request_hash, status, created_at)
            VALUES
                (#{entity.id}, #{entity.afterSaleNo}, #{entity.returnReceiptNo}, #{entity.orderNo},
                 #{entity.userId}, #{entity.warehouseId}, #{entity.reservationNo},
                 #{entity.requestHash}, #{entity.status}, #{entity.createdAt})
            """)
    int insertIfAbsent(@Param("entity") InventoryReturnEntity entity);

    @Select("SELECT * FROM inventory_return WHERE after_sale_no = #{afterSaleNo} FOR UPDATE")
    InventoryReturnEntity selectByAfterSaleNoForUpdate(@Param("afterSaleNo") String afterSaleNo);
}
