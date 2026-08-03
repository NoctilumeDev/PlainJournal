package com.ecommerce.fulfillment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("fulfillment_order")
public class FulfillmentOrderEntity {
    @TableId
    private Long id;
    private String fulfillmentNo;
    private String orderNo;
    private Long userId;
    private Long sourceAddressId;
    private String recipientName;
    private String phone;
    private String province;
    private String provinceCode;
    private String city;
    private String cityCode;
    private String district;
    private String districtCode;
    private String detailAddress;
    private String postalCode;
    private String status;
    private String carrier;
    private String trackingNo;
    private Long latestPositionTraceId;
    private Instant latestPositionAt;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant pickedAt;
    private Instant packedAt;
    private Instant shippedAt;
    private Instant signedAt;
}
