package com.ecommerce.marketing.application.exception;

public enum MarketingError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested marketing resource does not exist"),
    INVALID_RULE("INVALID_RULE", "The marketing rule is invalid"),
    BENEFIT_NOT_ELIGIBLE("BENEFIT_NOT_ELIGIBLE", "A selected benefit is not eligible for this order"),
    DUPLICATE_BENEFIT_TYPE("DUPLICATE_BENEFIT_TYPE", "Only one benefit of each type can be used per order"),
    INVALID_PRICING_REQUEST("INVALID_PRICING_REQUEST", "The pricing request is inconsistent"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different data"),
    INVALID_STATE("INVALID_STATE", "The pricing lock is not in a valid state for this operation"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The marketing resource was modified concurrently");

    private final String code;
    private final String message;

    MarketingError(String code, String message) {
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
