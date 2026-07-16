package com.ecommerce.inventory.application.exception;

public enum InventoryError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested inventory resource does not exist"),
    INVALID_STATE("INVALID_STATE", "The inventory resource is not in a valid state for this operation"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different request"),
    INVALID_ADJUSTMENT("INVALID_ADJUSTMENT", "The stock adjustment would violate inventory constraints"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The inventory resource was modified by another request");

    private final String code;
    private final String message;

    InventoryError(String code, String message) {
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
