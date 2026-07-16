package com.ecommerce.fulfillment.application.exception;

public enum FulfillmentError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested fulfillment resource does not exist"),
    ACCESS_DENIED("ACCESS_DENIED", "The fulfillment resource does not belong to the current user"),
    INVALID_STATE("INVALID_STATE", "The fulfillment is not in a valid state for this operation"),
    INVALID_TRACE("INVALID_TRACE", "The logistics trace is not valid"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The event identifier was already used for different data"),
    DUPLICATE_TRACKING("DUPLICATE_TRACKING", "The carrier tracking number is already in use"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The fulfillment was modified by another request");

    private final String code;
    private final String message;

    FulfillmentError(String code, String message) {
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
