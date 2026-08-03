package com.ecommerce.fulfillment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentExceptionResolutionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FulfillmentExceptionResolutionMapper
        extends BaseMapper<FulfillmentExceptionResolutionEntity> {

    @Insert("""
            INSERT INTO fulfillment_exception_resolution
                (id, command_id, request_hash, fulfillment_id, fulfillment_no,
                 resume_status, operator_id, reason, created_at)
            VALUES
                (#{entity.id}, #{entity.commandId}, #{entity.requestHash},
                 #{entity.fulfillmentId}, #{entity.fulfillmentNo},
                 #{entity.resumeStatus}, #{entity.operatorId},
                 #{entity.reason}, #{entity.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(
            @Param("entity") FulfillmentExceptionResolutionEntity entity);

    @Select("""
            SELECT * FROM fulfillment_exception_resolution
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    FulfillmentExceptionResolutionEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);
}
