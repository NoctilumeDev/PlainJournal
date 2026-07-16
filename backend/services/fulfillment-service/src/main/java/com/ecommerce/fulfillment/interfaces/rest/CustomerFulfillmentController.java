package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.FulfillmentView;
import com.ecommerce.fulfillment.application.service.FulfillmentService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/orders")
public class CustomerFulfillmentController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final FulfillmentService fulfillmentService;

    public CustomerFulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping
    public ApiResponse<List<FulfillmentView>> orders(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.listForUser(Long.valueOf(jwt.getSubject())));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<FulfillmentView> order(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.getForUser(orderNo, Long.valueOf(jwt.getSubject())));
    }
}
