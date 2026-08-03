package com.ecommerce.trade.application.exception;

public enum TradeError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested trade resource does not exist"),
    FORBIDDEN("FORBIDDEN", "The trade resource belongs to another user"),
    INVALID_STATE("INVALID_STATE", "The order is not in a valid state for this operation"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different request"),
    PRODUCT_UNAVAILABLE("PRODUCT_UNAVAILABLE", "A requested product or SKU is unavailable"),
    ADDRESS_UNAVAILABLE("ADDRESS_UNAVAILABLE", "The delivery address is unavailable"),
    CART_LIMIT_EXCEEDED("CART_LIMIT_EXCEEDED", "The cart item limit was exceeded"),
    INVALID_CART_MERGE("INVALID_CART_MERGE", "The guest cart merge request is invalid"),
    INVALID_CURSOR("INVALID_CURSOR", "The order continuation cursor is invalid"),
    REMOTE_DEPENDENCY_UNAVAILABLE("REMOTE_DEPENDENCY_UNAVAILABLE", "A required service is temporarily unavailable"),
    AFTER_SALE_ALREADY_EXISTS("AFTER_SALE_ALREADY_EXISTS", "The order already has an after-sale request"),
    AFTER_SALE_WINDOW_EXPIRED("AFTER_SALE_WINDOW_EXPIRED", "The after-sale application window has expired"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The order was modified by another request");

    private final String code;
    private final String message;

    TradeError(String code, String message) {
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
