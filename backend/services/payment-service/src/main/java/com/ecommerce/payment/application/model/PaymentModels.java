package com.ecommerce.payment.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentModels {

    private PaymentModels() {
    }

    public record CreatePaymentCommand(Long userId, String idempotencyKey, String orderNo, String channel) {
    }

    public record PaymentView(
            String paymentNo,
            String orderNo,
            String channel,
            String status,
            BigDecimal amount,
            String channelTransactionNo,
            Instant paidAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CallbackCommand(
            String paymentNo,
            String externalEventId,
            String externalTransactionNo,
            String status,
            BigDecimal amount,
            long timestamp,
            String signature,
            String rawPayload
    ) {
    }

    public record RefundRequestedCommand(
            String eventId,
            String afterSaleNo,
            String orderNo,
            Long userId,
            BigDecimal amount
    ) {
    }

    public record RefundCallbackCommand(
            String refundNo,
            String externalEventId,
            String externalRefundNo,
            String status,
            BigDecimal amount,
            long timestamp,
            String signature,
            String rawPayload
    ) {
    }

    public record RetryRefundDispatchCommand(
            String refundNo,
            String commandId,
            String operatorId,
            String reason
    ) {
    }

    public record CreatePaymentExceptionRefundCommand(
            String paymentNo,
            String commandId,
            String operatorId,
            String reason
    ) {
    }

    public record PaymentExceptionRefundAuditView(
            String commandId,
            String paymentNo,
            String orderNo,
            String refundNo,
            String operatorId,
            String reason,
            String outcome,
            String errorCode,
            Instant createdAt
    ) {
    }

    public record RefundDispatchRetryAuditView(
            String commandId,
            String refundNo,
            String operatorId,
            String reason,
            String outcome,
            String errorCode,
            String beforeRefundStatus,
            String beforeRequestStatus,
            Integer beforeRequestAttempts,
            String beforeLastError,
            String afterRefundStatus,
            String afterRequestStatus,
            Integer afterRequestAttempts,
            Instant createdAt
    ) {
    }

    public record ReconciliationIssueView(
            String domain,
            String referenceNo,
            String issueType,
            String status,
            int occurrences,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            Instant resolvedAt
    ) {
    }

    public record RefundView(
            String refundNo,
            String afterSaleNo,
            String orderNo,
            String paymentNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long userId,
            String channel,
            String status,
            BigDecimal amount,
            String channelRefundNo,
            String requestStatus,
            int requestAttempts,
            Instant nextRequestAt,
            Instant requestSentAt,
            Instant createdAt,
            Instant updatedAt,
            Instant refundedAt
    ) {
    }
}
