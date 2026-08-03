package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ShipmentLatestPositionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShipmentLatestPositionMapper extends BaseMapper<ShipmentLatestPositionEntity> {

    @Select("""
            SELECT *
            FROM shipment_latest_position
            WHERE fulfillment_id = #{fulfillmentId}
            FOR UPDATE
            """)
    ShipmentLatestPositionEntity selectByFulfillmentIdForUpdate(
            @Param("fulfillmentId") Long fulfillmentId);

    @Select("""
            SELECT *
            FROM shipment_latest_position
            ORDER BY occurred_at DESC, fulfillment_id DESC
            LIMIT #{limit}
            """)
    List<ShipmentLatestPositionEntity> selectLatest(@Param("limit") int limit);
}
