package com.ecommerce.fulfillment.infrastructure.persistence.entity;

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
@TableName("return_receipt")
public class ReturnReceiptEntity {
    @TableId
    private Long id;
    private String returnReceiptNo;
    private String afterSaleNo;
    private String orderNo;
    private Long userId;
    private Long warehouseId;
    private String reservationNo;
    private String status;
    private BigDecimal refundAmount;
    private String carrier;
    private String trackingNo;
    private String inspectionRemark;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant shippedAt;
    private Instant receivedAt;
    private Instant inspectedAt;
}
