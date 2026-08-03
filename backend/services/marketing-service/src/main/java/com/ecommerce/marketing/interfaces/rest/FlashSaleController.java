package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleActivityView;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleAdmissionView;
import com.ecommerce.marketing.application.service.FlashSaleService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/marketing/flash-sales")
public class FlashSaleController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final FlashSaleService flashSaleService;

    public FlashSaleController(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @GetMapping("/{activityNo}")
    public ApiResponse<FlashSaleActivityView> activity(
            @PathVariable @Size(max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String activityNo) {
        return ApiResponse.success(flashSaleService.getActivity(activityNo));
    }

    @PostMapping("/{activityNo}/admissions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<FlashSaleAdmissionView> admit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String activityNo,
            @RequestHeader("Idempotency-Key")
            @Size(min = 8, max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey,
            @Valid @RequestBody FlashSaleAdmissionRequest request) {
        return ApiResponse.success(flashSaleService.admit(
                Long.valueOf(jwt.getSubject()),
                activityNo,
                idempotencyKey,
                request.addressId()));
    }

    @GetMapping("/admissions/{requestToken}")
    public ApiResponse<FlashSaleAdmissionView> admission(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String requestToken) {
        return ApiResponse.success(flashSaleService.getAdmission(
                Long.valueOf(jwt.getSubject()),
                requestToken));
    }

    public record FlashSaleAdmissionRequest(
            @NotNull @Positive Long addressId
    ) {
    }
}
