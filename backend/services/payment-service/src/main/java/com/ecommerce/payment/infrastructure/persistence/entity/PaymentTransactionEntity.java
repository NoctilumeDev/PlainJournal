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
@TableName("payment_transaction")
public class PaymentTransactionEntity {
    @TableId
    private Long id;
    private Long paymentId;
    private String transactionType;
    private String channel;
    private String channelTransactionNo;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
}
