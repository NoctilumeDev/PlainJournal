package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.MarketingModels.BenefitView;
import com.ecommerce.marketing.application.model.MarketingModels.DeliveryRegion;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLine;
import com.ecommerce.marketing.application.model.MarketingModels.PricingPreviewView;
import com.ecommerce.marketing.application.model.MarketingModels.PreviewPricingCommand;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/marketing")
public class CustomerMarketingController {

    private final MarketingService marketingService;

    public CustomerMarketingController(MarketingService marketingService) {
        this.marketingService = marketingService;
    }

    @GetMapping("/benefits")
    public ApiResponse<List<BenefitView>> benefits(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(marketingService.listBenefits(Long.valueOf(jwt.getSubject())));
    }

    @PostMapping("/pricing-previews")
    public ApiResponse<PricingPreviewView> preview(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PricingPreviewRequest request) {
        return ApiResponse.success(marketingService.previewPricing(
                request.toCommand(Long.valueOf(jwt.getSubject()))));
    }

    public record PricingPreviewRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal originalAmount,
            @NotNull @Valid DeliveryRegionRequest deliveryRegion,
            @NotEmpty @Size(max = 100) List<@Valid PricingLineRequest> lines,
            @Size(max = 3) List<@NotBlank @Size(max = 64) String> benefitNos
    ) {
        PreviewPricingCommand toCommand(Long userId) {
            return new PreviewPricingCommand(userId, originalAmount, deliveryRegion.toModel(),
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
