package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundTransactionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RefundTransactionMapper extends BaseMapper<RefundTransactionEntity> {

    @Select("""
            SELECT * FROM refund_transaction
            WHERE channel = #{channel} AND channel_refund_no = #{channelRefundNo}
            FOR UPDATE
            """)
    RefundTransactionEntity selectByChannelRefundNoForUpdate(
            @Param("channel") String channel,
            @Param("channelRefundNo") String channelRefundNo);
}
