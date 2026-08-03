package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentTransactionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PaymentTransactionMapper extends BaseMapper<PaymentTransactionEntity> {

    @Select("""
            SELECT * FROM payment_transaction
            WHERE channel = #{channel} AND channel_transaction_no = #{channelTransactionNo}
            FOR UPDATE
            """)
    PaymentTransactionEntity selectByChannelTransactionNoForUpdate(
            @Param("channel") String channel,
            @Param("channelTransactionNo") String channelTransactionNo);
}
