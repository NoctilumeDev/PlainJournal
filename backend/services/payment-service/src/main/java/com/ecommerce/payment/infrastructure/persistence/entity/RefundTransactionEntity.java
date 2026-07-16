package com.ecommerce.payment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("refund_transaction")
public class RefundTransactionEntity {
    @TableId
    private Long id;
    private Long refundId;
    private String channel;
    private String channelRefundNo;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
}
