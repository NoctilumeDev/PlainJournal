package com.ecommerce.identity.interfaces.rest;

import com.ecommerce.identity.application.model.AddressModels.AddressView;
import com.ecommerce.identity.application.service.AddressService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/identity/internal/users/{userId}/addresses")
public class InternalAddressController {

    private final AddressService addressService;

    public InternalAddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/{addressId}")
    public ApiResponse<AddressView> address(
            @PathVariable @Positive Long userId,
            @PathVariable @Positive Long addressId) {
        return ApiResponse.success(addressService.getOwned(userId, addressId));
    }
}
