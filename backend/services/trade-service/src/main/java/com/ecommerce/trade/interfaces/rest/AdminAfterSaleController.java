package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleView;
import com.ecommerce.trade.application.model.TradeModels.ReviewAfterSaleCommand;
import com.ecommerce.trade.application.service.AfterSaleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/trade/admin/after-sales")
public class AdminAfterSaleController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final AfterSaleService afterSaleService;

    public AdminAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @GetMapping
    public ApiResponse<List<AfterSaleView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(afterSaleService.list(status));
    }

    @GetMapping("/{afterSaleNo}")
    public ApiResponse<AfterSaleView> get(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String afterSaleNo) {
        return ApiResponse.success(afterSaleService.get(afterSaleNo));
    }

    @PostMapping("/{afterSaleNo}/review")
    public ApiResponse<AfterSaleView> review(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String afterSaleNo,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(afterSaleService.review(new ReviewAfterSaleCommand(
                afterSaleNo, request.approved(), request.reason(), jwt.getSubject())));
    }

    public record ReviewRequest(
            @NotNull Boolean approved,
            @NotBlank @Size(max = 500) String reason
    ) {
    }
}
