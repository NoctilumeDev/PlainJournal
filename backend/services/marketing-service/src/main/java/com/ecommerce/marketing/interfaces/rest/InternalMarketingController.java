package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.MarketingModels.DeliveryRegion;
import com.ecommerce.marketing.application.model.MarketingModels.LockPricingCommand;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLine;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLockView;
import com.ecommerce.marketing.application.service.MarketingService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/marketing/internal")
public class InternalMarketingController {

    private final MarketingService marketingService;

    public InternalMarketingController(MarketingService marketingService) {
        this.marketingService = marketingService;
    }

    @PostMapping("/pricing-locks")
    public ApiResponse<PricingLockView> lock(@Valid @RequestBody LockPricingRequest request) {
        return ApiResponse.success(marketingService.lockPricing(request.toCommand()));
    }

    @GetMapping("/pricing-locks/orders/{orderNo}")
    public ApiResponse<PricingLockView> get(@PathVariable @NotBlank @Size(max = 64) String orderNo) {
        return ApiResponse.success(marketingService.getLock(orderNo));
    }

    @PostMapping("/pricing-locks/orders/{orderNo}/release")
    public ApiResponse<PricingLockView> release(@PathVariable @NotBlank @Size(max = 64) String orderNo) {
        return ApiResponse.success(marketingService.release(orderNo));
    }

    @PostMapping("/pricing-locks/orders/{orderNo}/redeem")
    public ApiResponse<PricingLockView> redeem(@PathVariable @NotBlank @Size(max = 64) String orderNo) {
        return ApiResponse.success(marketingService.redeem(orderNo));
    }

    public record LockPricingRequest(
            @NotBlank @Size(max = 64) String orderNo,
            @NotNull @Positive Long userId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal originalAmount,
            @NotNull @Valid DeliveryRegionRequest deliveryRegion,
            @NotEmpty @Size(max = 100) List<@Valid PricingLineRequest> lines,
            @Size(max = 3) List<@NotBlank @Size(max = 64) String> benefitNos
    ) {
        LockPricingCommand toCommand() {
            return new LockPricingCommand(orderNo, userId, originalAmount, deliveryRegion.toModel(),
                    lines.stream().map(PricingLineRequest::toModel).toList(), benefitNos);
        }
    }

    public record DeliveryRegionRequest(
            @Pattern(regexp = "\\d{6}") String provinceCode,
            @Pattern(regexp = "\\d{6}") String cityCode,
            @Pattern(regexp = "\\d{6}") String districtCode
    ) {
        DeliveryRegion toModel() {
            return new DeliveryRegion(provinceCode, cityCode, districtCode);
        }
    }

    public record PricingLineRequest(
            @Positive int lineNo,
            @NotNull @Positive Long skuId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal lineAmount
    ) {
        PricingLine toModel() {
            return new PricingLine(lineNo, skuId, lineAmount);
        }
    }
}
