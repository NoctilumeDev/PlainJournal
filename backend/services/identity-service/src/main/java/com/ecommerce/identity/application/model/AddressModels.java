package com.ecommerce.identity.application.model;

import java.time.Instant;

public final class AddressModels {

    private AddressModels() {
    }

    public record AddressCommand(
            String recipientName,
            String phone,
            String province,
            String provinceCode,
            String city,
            String cityCode,
            String district,
            String districtCode,
            String detailAddress,
            String postalCode,
            boolean setDefault
    ) {
    }

    public record AddressView(
            Long id,
            String recipientName,
            String phone,
            String province,
            String provinceCode,
            String city,
            String cityCode,
            String district,
            String districtCode,
            String detailAddress,
            String postalCode,
            boolean defaultAddress,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
