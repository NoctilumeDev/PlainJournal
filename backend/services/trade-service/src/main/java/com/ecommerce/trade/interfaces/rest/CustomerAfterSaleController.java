package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleView;
import com.ecommerce.trade.application.model.TradeModels.ApplyAfterSaleCommand;
import com.ecommerce.trade.application.service.AfterSaleService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/trade")
public class CustomerAfterSaleController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final AfterSaleService afterSaleService;

    public CustomerAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @PostMapping("/orders/{orderNo}/after-sales")
    public ApiResponse<AfterSaleView> apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo,
            @RequestHeader("Idempotency-Key")
            @Size(min = 8, max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey,
            @Valid @RequestBody ApplyRequest request) {
        return ApiResponse.success(afterSaleService.apply(new ApplyAfterSaleCommand(
                Long.valueOf(jwt.getSubject()), idempotencyKey, orderNo, request.reason())));
    }

    @GetMapping("/after-sales")
    public ApiResponse<List<AfterSaleView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(afterSaleService.listForUser(Long.valueOf(jwt.getSubject())));
    }

    @GetMapping("/after-sales/{afterSaleNo}")
    public ApiResponse<AfterSaleView> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String afterSaleNo) {
        return ApiResponse.success(afterSaleService.getForUser(Long.valueOf(jwt.getSubject()), afterSaleNo));
    }

    @PostMapping("/after-sales/{afterSaleNo}/cancel")
    public ApiResponse<AfterSaleView> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String afterSaleNo) {
        return ApiResponse.success(afterSaleService.cancel(Long.valueOf(jwt.getSubject()), afterSaleNo));
    }

    public record ApplyRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
