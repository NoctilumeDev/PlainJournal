package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentStatusHistoryEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FulfillmentStatusHistoryMapper extends BaseMapper<FulfillmentStatusHistoryEntity> {

    @Select("""
            SELECT from_status FROM fulfillment_status_history
            WHERE fulfillment_id = #{fulfillmentId}
              AND to_status = 'EXCEPTION'
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    String selectLatestExceptionSourceStatus(
            @Param("fulfillmentId") Long fulfillmentId);
}
