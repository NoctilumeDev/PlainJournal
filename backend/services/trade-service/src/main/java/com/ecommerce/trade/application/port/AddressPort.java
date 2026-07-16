package com.ecommerce.trade.application.port;

public interface AddressPort {

    AddressSnapshot getAddress(Long userId, Long addressId);

    record AddressSnapshot(
            Long addressId,
            String recipientName,
            String phone,
            String province,
            String provinceCode,
            String city,
            String cityCode,
            String district,
            String districtCode,
            String detailAddress,
            String postalCode
    ) {
    }
}
