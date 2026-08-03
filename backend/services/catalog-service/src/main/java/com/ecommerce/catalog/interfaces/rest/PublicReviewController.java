package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewSummaryView;
import com.ecommerce.catalog.application.routing.CatalogReplicaRead;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.api.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/catalog/products/{productId}")
public class PublicReviewController {

    private final ProductReviewService service;

    public PublicReviewController(ProductReviewService service) {
        this.service = service;
    }

    @GetMapping("/review-summary")
    @CatalogReplicaRead
    public ApiResponse<ReviewSummaryView> summary(
            @PathVariable @Positive long productId) {
        return ApiResponse.success(service.summary(productId));
    }

    @GetMapping("/reviews")
    @CatalogReplicaRead
    public ApiResponse<PageResponse<ProductReviewView>> reviews(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long productId,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.success(service.listPublished(
                productId,
                userIdOrNull(jwt),
                page,
                size));
    }

    private Long userIdOrNull(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        try {
            long userId = Long.parseLong(jwt.getSubject());
            return userId > 0 ? userId : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
