package com.ecommerce.identity.interfaces.rest;

import com.ecommerce.identity.application.model.AddressModels.AddressCommand;
import com.ecommerce.identity.application.model.AddressModels.AddressView;
import com.ecommerce.identity.application.service.AddressService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/identity/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @PostMapping
    public ApiResponse<AddressView> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddressRequest request) {
        return ApiResponse.success(addressService.create(userId(jwt), request.toCommand()));
    }

    @GetMapping
    public ApiResponse<List<AddressView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(addressService.list(userId(jwt)));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<AddressView> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long addressId,
            @Valid @RequestBody AddressRequest request) {
        return ApiResponse.success(addressService.update(userId(jwt), addressId, request.toCommand()));
    }

    @PostMapping("/{addressId}/default")
    public ApiResponse<AddressView> setDefault(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long addressId) {
        return ApiResponse.success(addressService.setDefault(userId(jwt), addressId));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long addressId) {
        addressService.delete(userId(jwt), addressId);
        return ApiResponse.success(null);
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    public record AddressRequest(
            @NotBlank @Size(max = 60) String recipientName,
            @NotBlank @Size(max = 30) @Pattern(regexp = "\\+?[0-9 -]{6,29}") String phone,
            @NotBlank @Size(max = 60) String province,
            @NotBlank @Pattern(regexp = "\\d{6}") String provinceCode,
            @NotBlank @Size(max = 60) String city,
            @NotBlank @Pattern(regexp = "\\d{6}") String cityCode,
            @NotBlank @Size(max = 60) String district,
            @NotBlank @Pattern(regexp = "\\d{6}") String districtCode,
            @NotBlank @Size(max = 240) String detailAddress,
            @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9 -]*") String postalCode,
            boolean setDefault
    ) {
        AddressCommand toCommand() {
            return new AddressCommand(recipientName, phone, province, provinceCode, city, cityCode,
                    district, districtCode, detailAddress, postalCode, setDefault);
        }
    }
}
