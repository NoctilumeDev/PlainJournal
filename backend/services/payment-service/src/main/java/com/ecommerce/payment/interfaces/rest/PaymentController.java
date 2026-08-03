package com.ecommerce.payment.interfaces.rest;

import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.service.PaymentService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Validated
@RestController
@RequestMapping("/api/v1/payment")
public class PaymentController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/payments")
    public ApiResponse<PaymentView> createPayment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @Size(min = 8, max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return ApiResponse.success(paymentService.createPayment(new CreatePaymentCommand(
                Long.valueOf(jwt.getSubject()), idempotencyKey, request.orderNo(), request.channel())));
    }

    @GetMapping("/payments/{paymentNo}")
    public ApiResponse<PaymentView> payment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String paymentNo) {
        return ApiResponse.success(paymentService.getPayment(Long.valueOf(jwt.getSubject()), paymentNo));
    }

    @GetMapping("/payments/by-idempotency-key/{key}")
    public ApiResponse<PaymentView> paymentByIdempotencyKey(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(min = 8, max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String key) {
        return ApiResponse.success(paymentService.getPaymentByIdempotencyKey(
                Long.valueOf(jwt.getSubject()), key));
    }

    @GetMapping("/payments/by-order/{orderNo}")
    public ApiResponse<PaymentView> paymentByOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo) {
        return ApiResponse.success(paymentService.getPaymentByOrder(
                Long.valueOf(jwt.getSubject()), orderNo));
    }

    @PostMapping("/callbacks/mock")
    public ApiResponse<PaymentView> mockCallback(@Valid @RequestBody MockCallbackRequest request) {
        return ApiResponse.success(paymentService.processMockCallback(new CallbackCommand(
                request.paymentNo(), request.externalEventId(), request.externalTransactionNo(),
                request.status(), request.amount(), request.timestamp(), request.signature(), raw(request))));
    }

    private String raw(MockCallbackRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Callback payload cannot be serialized", exception);
        }
    }

    public record CreatePaymentRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo,
            @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z]+") String channel
    ) {
    }

    public record MockCallbackRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String paymentNo,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String externalEventId,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String externalTransactionNo,
            @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String status,
            @NotNull @DecimalMin("0.00") BigDecimal amount,
            long timestamp,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String signature
    ) {
    }
}
