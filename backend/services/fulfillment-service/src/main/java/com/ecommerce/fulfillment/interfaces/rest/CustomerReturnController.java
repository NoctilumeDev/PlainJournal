package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.ReturnReceiptView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.SubmitReturnShipmentCommand;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/returns")
public class CustomerReturnController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final ReturnReceiptService returnReceiptService;

    public CustomerReturnController(ReturnReceiptService returnReceiptService) {
        this.returnReceiptService = returnReceiptService;
    }

    @GetMapping
    public ApiResponse<List<ReturnReceiptView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(returnReceiptService.listForUser(Long.valueOf(jwt.getSubject())));
    }

    @GetMapping("/{returnReceiptNo}")
    public ApiResponse<ReturnReceiptView> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String returnReceiptNo) {
        return ApiResponse.success(returnReceiptService.getForUser(
                Long.valueOf(jwt.getSubject()), returnReceiptNo));
    }

    @PostMapping("/{returnReceiptNo}/shipment")
    public ApiResponse<ReturnReceiptView> submitShipment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String returnReceiptNo,
            @Valid @RequestBody SubmitShipmentRequest request) {
        return ApiResponse.success(returnReceiptService.submitShipment(
                Long.valueOf(jwt.getSubject()), returnReceiptNo,
                new SubmitReturnShipmentCommand(request.carrier(), request.trackingNo())));
    }

    public record SubmitShipmentRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String carrier,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String trackingNo
    ) {
    }
}
