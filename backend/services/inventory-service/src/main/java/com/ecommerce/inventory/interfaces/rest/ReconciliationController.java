package com.ecommerce.inventory.interfaces.rest;

import com.ecommerce.inventory.application.model.InventoryModels.ReconciliationIssueView;
import com.ecommerce.inventory.application.service.InventoryReconciliationService;
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
@RequestMapping("/api/v1/inventory/admin/reconciliation")
public class ReconciliationController {

    private final InventoryReconciliationService service;

    public ReconciliationController(InventoryReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/issues")
    public ApiResponse<List<ReconciliationIssueView>> issues(
            @RequestParam(defaultValue = "OPEN")
            @Pattern(regexp = "OPEN|RESOLVED") String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.listIssues(status, limit));
    }
}
