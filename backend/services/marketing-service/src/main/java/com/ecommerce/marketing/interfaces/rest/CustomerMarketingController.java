package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.MarketingModels.BenefitView;
import com.ecommerce.marketing.application.service.MarketingService;
import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/marketing/benefits")
public class CustomerMarketingController {

    private final MarketingService marketingService;

    public CustomerMarketingController(MarketingService marketingService) {
        this.marketingService = marketingService;
    }

    @GetMapping
    public ApiResponse<List<BenefitView>> benefits(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(marketingService.listBenefits(Long.valueOf(jwt.getSubject())));
    }
}
