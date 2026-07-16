package com.ecommerce.payment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("payment_order")
public class PaymentOrderEntity {
    @TableId
    private Long id;
    private String paymentNo;
    private String orderNo;
    private Long userId;
    private String reservationNo;
    private String idempotencyKey;
    private String requestHash;
    private String channel;
    private String status;
    private BigDecimal amount;
    private String channelTransactionNo;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;
}
