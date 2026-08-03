package com.ecommerce.payment.interfaces.rest;

import com.ecommerce.payment.application.model.PaymentModels.ReconciliationIssueView;
import com.ecommerce.payment.application.service.PaymentReconciliationService;
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
@RequestMapping("/api/v1/payment/admin/reconciliation")
public class ReconciliationController {

    private final PaymentReconciliationService service;

    public ReconciliationController(PaymentReconciliationService service) {
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
