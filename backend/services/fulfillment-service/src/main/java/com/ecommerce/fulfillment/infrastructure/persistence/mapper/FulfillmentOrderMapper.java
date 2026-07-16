package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentOrderEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FulfillmentOrderMapper extends BaseMapper<FulfillmentOrderEntity> {

    @Select("SELECT * FROM fulfillment_order WHERE fulfillment_no = #{fulfillmentNo} FOR UPDATE")
    FulfillmentOrderEntity selectByFulfillmentNoForUpdate(@Param("fulfillmentNo") String fulfillmentNo);

    @Select("SELECT * FROM fulfillment_order WHERE order_no = #{orderNo} FOR UPDATE")
    FulfillmentOrderEntity selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
