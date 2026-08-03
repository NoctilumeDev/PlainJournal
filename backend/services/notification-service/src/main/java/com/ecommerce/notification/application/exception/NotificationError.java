package com.ecommerce.notification.application.exception;

public enum NotificationError {
    NOTIFICATION_NOT_FOUND("NOTIFICATION_NOT_FOUND", "The requested notification does not exist"),
    DELIVERY_NOT_FOUND("DELIVERY_NOT_FOUND", "The requested notification delivery does not exist"),
    DELIVERY_RETRY_NOT_ALLOWED(
            "DELIVERY_RETRY_NOT_ALLOWED",
            "The notification delivery cannot be retried from its current state"),
    IDEMPOTENCY_CONFLICT(
            "IDEMPOTENCY_CONFLICT",
            "The idempotency key was already used with a different request"),
    INVALID_CURSOR("INVALID_CURSOR", "The notification cursor is invalid");

    private final String code;
    private final String message;

    NotificationError(String code, String message) {
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
