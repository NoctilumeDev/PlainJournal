package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.ReviewModels.CreateReviewCommand;
import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewEligibilityView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportReceipt;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/catalog")
public class CustomerReviewController {

    private final ProductReviewService service;

    public CustomerReviewController(ProductReviewService service) {
        this.service = service;
    }

    @GetMapping("/review-eligibilities")
    public ApiResponse<List<ReviewEligibilityView>> eligibilities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 64) String orderNo) {
        return ApiResponse.success(service.listEligibilities(userId(jwt), orderNo));
    }

    @PostMapping("/reviews")
    public ApiResponse<ProductReviewView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateReviewRequest request) {
        return ApiResponse.success(service.createReview(new CreateReviewCommand(
                userId(jwt),
                request.eligibilityId(),
                request.rating(),
                request.content(),
                request.anonymous(),
                idempotencyKey)));
    }

    @PostMapping("/reviews/{reviewId}/likes")
    public ApiResponse<ProductReviewView> like(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long reviewId) {
        return ApiResponse.success(service.like(userId(jwt), reviewId));
    }

    @DeleteMapping("/reviews/{reviewId}/likes")
    public ApiResponse<ProductReviewView> unlike(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long reviewId) {
        return ApiResponse.success(service.unlike(userId(jwt), reviewId));
    }

    @PostMapping("/reviews/{reviewId}/reports")
    public ApiResponse<ReviewReportReceipt> report(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long reviewId,
            @Valid @RequestBody ReportReviewRequest request) {
        return ApiResponse.success(service.report(
                userId(jwt),
                reviewId,
                request.reasonCode(),
                request.detail()));
    }

    private long userId(Jwt jwt) {
        try {
            long value = Long.parseLong(jwt.getSubject());
            if (value <= 0) {
                throw new IllegalArgumentException("JWT subject must be positive");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JWT subject is invalid", exception);
        }
    }

    public record CreateReviewRequest(
            @Positive Long eligibilityId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 2000) String content,
            boolean anonymous) {
    }

    public record ReportReviewRequest(
            @NotBlank
            @Pattern(regexp = "SPAM|ABUSE|FALSE_INFORMATION|OTHER")
            String reasonCode,
            @Size(max = 500) String detail) {
    }
}
