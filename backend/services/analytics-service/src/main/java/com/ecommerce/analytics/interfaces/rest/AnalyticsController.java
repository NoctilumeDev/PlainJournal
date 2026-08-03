package com.ecommerce.analytics.interfaces.rest;

import com.ecommerce.analytics.application.model.AnalyticsModels.DashboardView;
import com.ecommerce.analytics.application.model.AnalyticsModels.RebuildView;
import com.ecommerce.analytics.application.model.AnalyticsModels.ReconciliationView;
import com.ecommerce.analytics.application.service.AnalyticsApplicationService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsApplicationService service;

    public AnalyticsController(AnalyticsApplicationService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<DashboardView> overview(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false)
            Integer productLimit) {
        return ApiResponse.success(service.dashboard(from, to, productLimit));
    }

    @GetMapping("/admin/reconciliation")
    public ApiResponse<ReconciliationView> reconciliation(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ApiResponse.success(service.reconcile(from, to));
    }

    @PostMapping("/admin/rebuild")
    public ApiResponse<RebuildView> rebuild(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RebuildRequest request) {
        return ApiResponse.success(service.rebuild(
                operatorId(jwt),
                request.commandId(),
                request.reason(),
                request.from(),
                request.to()));
    }

    private long operatorId(Jwt jwt) {
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
            @NotBlank @Size(min = 8, max = 500) String reason,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }
}
