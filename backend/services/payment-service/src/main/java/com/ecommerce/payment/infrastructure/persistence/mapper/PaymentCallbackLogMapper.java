package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentCallbackLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PaymentCallbackLogMapper extends BaseMapper<PaymentCallbackLogEntity> {

    @Insert("""
            INSERT INTO payment_callback_log
                (id, channel, external_event_id, payment_no, request_hash, signature_valid,
                 processing_status, raw_payload, error_message, received_at, processed_at)
            VALUES
                (#{entity.id}, #{entity.channel}, #{entity.externalEventId}, #{entity.paymentNo},
                 #{entity.requestHash}, #{entity.signatureValid}, #{entity.processingStatus},
                 #{entity.rawPayload}, #{entity.errorMessage}, #{entity.receivedAt}, #{entity.processedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") PaymentCallbackLogEntity entity);

    @Select("""
            SELECT * FROM payment_callback_log
            WHERE channel = #{channel} AND external_event_id = #{externalEventId}
            FOR UPDATE
            """)
    PaymentCallbackLogEntity selectForUpdate(
            @Param("channel") String channel,
            @Param("externalEventId") String externalEventId);
}
