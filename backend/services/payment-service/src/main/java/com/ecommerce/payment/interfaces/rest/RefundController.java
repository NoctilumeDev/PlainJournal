package com.ecommerce.payment.interfaces.rest;

import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentExceptionRefundCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentExceptionRefundAuditView;
import com.ecommerce.payment.application.model.PaymentModels.RefundDispatchRetryAuditView;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.application.model.PaymentModels.RetryRefundDispatchCommand;
import com.ecommerce.payment.application.service.RefundService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.math.BigDecimal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/payment")
public class RefundController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    public RefundController(RefundService refundService, ObjectMapper objectMapper) {
        this.refundService = refundService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/refunds/{refundNo}")
    public ApiResponse<RefundView> refund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String refundNo) {
        return ApiResponse.success(refundService.getForUser(Long.valueOf(jwt.getSubject()), refundNo));
    }

    @GetMapping("/refunds/by-after-sale/{afterSaleNo}")
    public ApiResponse<RefundView> refundByAfterSale(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String afterSaleNo) {
        return ApiResponse.success(
                refundService.getByAfterSaleNoForUser(Long.valueOf(jwt.getSubject()), afterSaleNo));
    }

    @PostMapping("/admin/refunds/{refundNo}/retry-dispatch")
    public ApiResponse<RefundView> retryDispatch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String refundNo,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String commandId,
            @Valid @RequestBody RetryRefundDispatchRequest request) {
        return ApiResponse.success(refundService.retryDispatch(new RetryRefundDispatchCommand(
                refundNo, commandId, jwt.getSubject(), request.reason())));
    }

    @GetMapping("/admin/refunds/{refundNo}/retry-dispatch/audits")
    public ApiResponse<List<RefundDispatchRetryAuditView>> retryDispatchAudits(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String refundNo,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(refundService.listRetryAudits(refundNo, limit));
    }

    @PostMapping("/admin/payments/{paymentNo}/exception-refunds")
    public ApiResponse<RefundView> createPaymentExceptionRefund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String paymentNo,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String commandId,
            @Valid @RequestBody CreatePaymentExceptionRefundRequest request) {
        return ApiResponse.success(refundService.createPaymentExceptionRefund(
                new CreatePaymentExceptionRefundCommand(
                        paymentNo,
                        commandId,
                        jwt.getSubject(),
                        request.reason())));
    }

    @GetMapping("/admin/payments/{paymentNo}/exception-refunds/audits")
    public ApiResponse<List<PaymentExceptionRefundAuditView>>
            paymentExceptionRefundAudits(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String paymentNo,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(
                refundService.listPaymentExceptionRefundAudits(paymentNo, limit));
    }

    @PostMapping("/callbacks/mock/refunds")
    public ApiResponse<RefundView> mockRefundCallback(@Valid @RequestBody MockRefundCallbackRequest request) {
        return ApiResponse.success(refundService.processMockCallback(new RefundCallbackCommand(
                request.refundNo(), request.externalEventId(), request.externalRefundNo(), request.status(),
                request.amount(), request.timestamp(), request.signature(), raw(request))));
    }

    private String raw(MockRefundCallbackRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Refund callback payload cannot be serialized", exception);
        }
    }

    public record MockRefundCallbackRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String refundNo,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String externalEventId,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String externalRefundNo,
            @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String status,
            @NotNull @DecimalMin("0.00") BigDecimal amount,
            long timestamp,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String signature
    ) {
    }

    public record RetryRefundDispatchRequest(
            @NotBlank @Size(max = 200) String reason
    ) {
    }

    public record CreatePaymentExceptionRefundRequest(
            @NotBlank @Size(max = 200) String reason
    ) {
    }
}
