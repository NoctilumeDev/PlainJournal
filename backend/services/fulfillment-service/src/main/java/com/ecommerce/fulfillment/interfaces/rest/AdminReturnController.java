package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.ReturnReceiptView;
import com.ecommerce.fulfillment.application.service.ReturnReceiptService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/admin/returns")
public class AdminReturnController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final ReturnReceiptService returnReceiptService;

    public AdminReturnController(ReturnReceiptService returnReceiptService) {
        this.returnReceiptService = returnReceiptService;
    }

    @GetMapping
    public ApiResponse<List<ReturnReceiptView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(returnReceiptService.list(status));
    }

    @GetMapping("/{returnReceiptNo}")
    public ApiResponse<ReturnReceiptView> get(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String returnReceiptNo) {
        return ApiResponse.success(returnReceiptService.get(returnReceiptNo));
    }

    @PostMapping("/{returnReceiptNo}/receive")
    public ApiResponse<ReturnReceiptView> receive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String returnReceiptNo) {
        return ApiResponse.success(returnReceiptService.receive(returnReceiptNo, jwt.getSubject()));
    }

    @PostMapping("/{returnReceiptNo}/inspect")
    public ApiResponse<ReturnReceiptView> inspect(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String returnReceiptNo,
            @Valid @RequestBody InspectRequest request) {
        return ApiResponse.success(returnReceiptService.inspect(
                returnReceiptNo, request.remark(), jwt.getSubject()));
    }

    public record InspectRequest(@NotBlank @Size(max = 500) String remark) {
    }
}
