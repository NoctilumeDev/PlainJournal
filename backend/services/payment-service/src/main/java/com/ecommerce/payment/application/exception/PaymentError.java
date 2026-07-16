package com.ecommerce.payment.application.exception;

public enum PaymentError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested payment does not exist"),
    FORBIDDEN("FORBIDDEN", "The payment belongs to another user"),
    INVALID_STATE("INVALID_STATE", "The payment or order is not in a valid state"),
    INVALID_SIGNATURE("INVALID_SIGNATURE", "The payment callback signature is invalid"),
    CALLBACK_EXPIRED("CALLBACK_EXPIRED", "The payment callback timestamp is outside the allowed window"),
    AMOUNT_MISMATCH("AMOUNT_MISMATCH", "The callback amount does not match the payment amount"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key or callback event was reused for different data"),
    UNSUPPORTED_CHANNEL("UNSUPPORTED_CHANNEL", "The requested payment channel is unsupported"),
    REMOTE_DEPENDENCY_UNAVAILABLE("REMOTE_DEPENDENCY_UNAVAILABLE", "A required service is temporarily unavailable"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The payment was modified by another request");

    private final String code;
    private final String message;

    PaymentError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
