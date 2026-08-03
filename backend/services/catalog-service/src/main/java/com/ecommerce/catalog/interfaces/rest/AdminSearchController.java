package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.SearchModels.SearchOutboxView;
import com.ecommerce.catalog.application.model.SearchModels.SearchRebuildRecoveryView;
import com.ecommerce.catalog.application.model.SearchModels.SearchRebuildView;
import com.ecommerce.catalog.application.model.SearchModels.SearchReconciliationIssueView;
import com.ecommerce.catalog.application.model.SearchModels.SearchReconciliationResult;
import com.ecommerce.catalog.application.model.SearchModels.SearchRecoveryView;
import com.ecommerce.catalog.application.service.CatalogSearchOutboxService;
import com.ecommerce.catalog.application.service.CatalogSearchRebuildService;
import com.ecommerce.catalog.application.service.CatalogSearchReconciliationService;
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
@RequestMapping("/api/v1/catalog/admin/search")
public class AdminSearchController {

    private final CatalogSearchOutboxService outboxService;
    private final CatalogSearchRebuildService rebuildService;
    private final CatalogSearchReconciliationService reconciliationService;

    public AdminSearchController(
            CatalogSearchOutboxService outboxService,
            CatalogSearchRebuildService rebuildService,
            CatalogSearchReconciliationService reconciliationService) {
        this.outboxService = outboxService;
        this.rebuildService = rebuildService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/outbox")
    public ApiResponse<List<SearchOutboxView>> outbox(
            @RequestParam(defaultValue = "NEEDS_ATTENTION")
            @Pattern(regexp = "PENDING|PROJECTING|PUBLISHED|NEEDS_ATTENTION") String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(outboxService.list(status, limit));
    }

    @PostMapping("/outbox/{outboxId}/recover")
    public ApiResponse<SearchRecoveryView> recoverOutbox(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(min = 36, max = 36) String outboxId,
            @Valid @RequestBody RecoveryRequest request) {
        return ApiResponse.success(outboxService.recover(
                outboxId,
                userId(jwt),
                request.commandId(),
                request.reason()));
    }

    @PostMapping("/rebuilds")
    public ApiResponse<SearchRebuildView> rebuild(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RebuildRequest request) {
        return ApiResponse.success(rebuildService.submit(
                userId(jwt),
                request.commandId(),
                request.reason()));
    }

    @GetMapping("/rebuilds/{rebuildId}")
    public ApiResponse<SearchRebuildView> rebuild(
            @PathVariable @Positive long rebuildId) {
        return ApiResponse.success(rebuildService.get(rebuildId));
    }

    @GetMapping("/rebuilds")
    public ApiResponse<List<SearchRebuildView>> rebuilds(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(rebuildService.list(limit));
    }

    @PostMapping("/rebuilds/{rebuildId}/recover")
    public ApiResponse<SearchRebuildRecoveryView> recoverRebuild(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long rebuildId,
            @Valid @RequestBody RecoveryRequest request) {
        return ApiResponse.success(rebuildService.recover(
                rebuildId,
                userId(jwt),
                request.commandId(),
                request.reason()));
    }

    @PostMapping("/reconciliation")
    public ApiResponse<SearchReconciliationResult> reconcile(
            @Valid @RequestBody ReconciliationRequest request) {
        return ApiResponse.success(reconciliationService.reconcile(request.repair()));
    }

    @GetMapping("/reconciliation/issues")
    public ApiResponse<List<SearchReconciliationIssueView>> reconciliationIssues(
            @RequestParam(defaultValue = "OPEN")
            @Pattern(regexp = "OPEN|RESOLVED") String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(reconciliationService.listIssues(status, limit));
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

    public record RebuildRequest(
            @NotBlank @Size(max = 64) String commandId,
            @NotBlank @Size(min = 8, max = 500) String reason) {
    }

    public record RecoveryRequest(
            @NotBlank @Size(max = 64) String commandId,
            @NotBlank @Size(min = 8, max = 500) String reason) {
    }

    public record ReconciliationRequest(boolean repair) {
    }
}
