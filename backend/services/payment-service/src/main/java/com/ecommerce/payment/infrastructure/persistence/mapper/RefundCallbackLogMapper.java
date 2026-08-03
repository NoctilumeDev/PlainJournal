package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundCallbackLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RefundCallbackLogMapper extends BaseMapper<RefundCallbackLogEntity> {

    @Insert("""
            INSERT INTO refund_callback_log
                (id, channel, external_event_id, refund_no, request_hash, signature_valid,
                 processing_status, raw_payload, error_message, received_at)
            VALUES
                (#{entity.id}, #{entity.channel}, #{entity.externalEventId}, #{entity.refundNo},
                 #{entity.requestHash}, #{entity.signatureValid}, #{entity.processingStatus},
                 #{entity.rawPayload}, #{entity.errorMessage}, #{entity.receivedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") RefundCallbackLogEntity entity);

    @Select("""
            SELECT * FROM refund_callback_log
            WHERE channel = #{channel} AND external_event_id = #{externalEventId}
            FOR UPDATE
            """)
    RefundCallbackLogEntity selectForUpdate(
            @Param("channel") String channel,
            @Param("externalEventId") String externalEventId);
}
