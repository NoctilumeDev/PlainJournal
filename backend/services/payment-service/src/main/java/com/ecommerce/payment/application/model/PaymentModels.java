package com.ecommerce.payment.application.model;

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

    public record RefundView(
            String refundNo,
            String afterSaleNo,
            String orderNo,
            String paymentNo,
            Long userId,
            String channel,
            String status,
            BigDecimal amount,
            String channelRefundNo,
            Instant createdAt,
            Instant updatedAt,
            Instant refundedAt
    ) {
    }
}
