package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.ReviewModels.ModerationResultView;
import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportView;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.api.PageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/catalog/admin/reviews")
public class AdminReviewController {

    private final ProductReviewService service;

    public AdminReviewController(ProductReviewService service) {
        this.service = service;
    }

    @GetMapping("/reports")
    public ApiResponse<PageResponse<ReviewReportView>> reports(
            @RequestParam(required = false)
            @Pattern(regexp = "OPEN|RESOLVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.success(service.listReports(status, page, size));
    }

    @PostMapping("/{reviewId}/reply")
    public ApiResponse<ProductReviewView> reply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long reviewId,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64) String commandId,
            @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.success(service.reply(
                userId(jwt),
                reviewId,
                commandId,
                request.content()));
    }

    @PostMapping("/reports/{reportId}/resolve")
    public ApiResponse<ModerationResultView> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        return ApiResponse.success(service.resolveReport(
                userId(jwt),
                reportId,
                request.commandId(),
                request.resolution(),
                request.reason()));
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

    public record ReplyRequest(
            @NotBlank @Size(max = 1000) String content) {
    }

    public record ResolveReportRequest(
            @NotBlank @Size(max = 64) String commandId,
            @NotBlank @Pattern(regexp = "UPHELD|REJECTED") String resolution,
            @NotBlank @Size(min = 8, max = 500) String reason) {
    }
}
