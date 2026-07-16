package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReservationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

public interface InventoryReservationMapper extends BaseMapper<InventoryReservationEntity> {

    @Insert("""
            INSERT IGNORE INTO inventory_reservation
                (id, reservation_no, order_no, request_hash, warehouse_id, status,
                 expires_at, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.reservationNo}, #{entity.orderNo}, #{entity.requestHash},
                 #{entity.warehouseId}, #{entity.status}, #{entity.expiresAt}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") InventoryReservationEntity entity);

    @Select("SELECT * FROM inventory_reservation WHERE reservation_no = #{reservationNo} FOR UPDATE")
    InventoryReservationEntity selectForUpdate(@Param("reservationNo") String reservationNo);

    @Select("""
            SELECT reservation_no FROM inventory_reservation
            WHERE status = 'RESERVED' AND expires_at <= #{now}
            ORDER BY expires_at, id
            LIMIT #{limit}
            """)
    List<String> selectExpiredReservationNumbers(
            @Param("now") Instant now,
            @Param("limit") int limit);
}
