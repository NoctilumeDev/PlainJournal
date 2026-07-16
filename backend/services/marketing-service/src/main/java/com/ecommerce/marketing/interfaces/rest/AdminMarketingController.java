package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.MarketingModels.CreateRuleCommand;
import com.ecommerce.marketing.application.model.MarketingModels.GrantBenefitCommand;
import com.ecommerce.marketing.application.model.MarketingModels.BenefitView;
import com.ecommerce.marketing.application.model.MarketingModels.RegionRestriction;
import com.ecommerce.marketing.application.model.MarketingModels.RuleView;
import com.ecommerce.marketing.application.service.MarketingService;
import com.ecommerce.marketing.domain.BenefitType;
import com.ecommerce.marketing.domain.RegionLevel;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/marketing/admin")
public class AdminMarketingController {

    private final MarketingService marketingService;

    public AdminMarketingController(MarketingService marketingService) {
        this.marketingService = marketingService;
    }

    @PostMapping("/rules")
    public ApiResponse<RuleView> createRule(@Valid @RequestBody CreateRuleRequest request) {
        return ApiResponse.success(marketingService.createRule(request.toCommand()));
    }

    @PostMapping("/benefits")
    public ApiResponse<BenefitView> grantBenefit(@Valid @RequestBody GrantBenefitRequest request) {
        return ApiResponse.success(marketingService.grantBenefit(
                new GrantBenefitCommand(request.userId(), request.ruleCode(), request.grantKey())));
    }

    public record CreateRuleRequest(
            @NotBlank @Size(max = 64) String ruleCode,
            @NotBlank @Size(max = 120) String name,
            @NotNull BenefitType benefitType,
            @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal thresholdAmount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
            @PositiveOrZero int stackOrder,
            @NotNull Instant validFrom,
            @NotNull @Future Instant validUntil,
            @Size(max = 100) List<@Valid RegionRequest> regions
    ) {
        CreateRuleCommand toCommand() {
            List<RegionRestriction> restrictions = regions == null ? List.of() : regions.stream()
                    .map(region -> new RegionRestriction(region.level(), region.regionCode())).toList();
            return new CreateRuleCommand(ruleCode, name, benefitType, thresholdAmount, discountAmount,
                    stackOrder, validFrom, validUntil, restrictions);
        }
    }

    public record RegionRequest(
            @NotNull RegionLevel level,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String regionCode
    ) {
    }

    public record GrantBenefitRequest(
            @NotNull @Positive Long userId,
            @NotBlank @Size(max = 64) String ruleCode,
            @NotBlank @Size(max = 100) String grantKey
    ) {
    }
}
