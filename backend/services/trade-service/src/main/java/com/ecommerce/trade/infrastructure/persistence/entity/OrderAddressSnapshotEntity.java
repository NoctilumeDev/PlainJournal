package com.ecommerce.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("order_address_snapshot")
public class OrderAddressSnapshotEntity {
    @TableId
    private Long id;
    private Long orderId;
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
    private Instant createdAt;
}
