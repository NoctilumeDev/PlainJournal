package com.ecommerce.identity.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("user_address")
public class UserAddressEntity {
    @TableId
    private Long id;
    private Long userId;
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
    private Boolean isDefault;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
