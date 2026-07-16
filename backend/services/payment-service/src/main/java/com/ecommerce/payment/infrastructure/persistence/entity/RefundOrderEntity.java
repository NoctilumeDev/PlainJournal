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
@TableName("refund_order")
public class RefundOrderEntity {
    @TableId
    private Long id;
    private String refundNo;
    private String afterSaleNo;
    private String orderNo;
    private Long paymentId;
    private String paymentNo;
    private Long userId;
    private String requestHash;
    private String channel;
    private String status;
    private BigDecimal amount;
    private String channelRefundNo;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant refundedAt;
}
