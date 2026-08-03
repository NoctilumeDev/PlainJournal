package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.service.FulfillmentReconciliationService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/admin/reconciliation")
public class ReconciliationController {

    private final FulfillmentReconciliationService service;

    public ReconciliationController(FulfillmentReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/issues")
    public ApiResponse<List<FulfillmentReconciliationService.ReconciliationIssueView>> issues(
            @RequestParam(defaultValue = "OPEN") @Pattern(regexp = "OPEN|RESOLVED") String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.listIssues(status, limit));
    }
}
