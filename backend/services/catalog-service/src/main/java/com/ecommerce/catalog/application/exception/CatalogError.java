package com.ecommerce.catalog.application.exception;

public enum CatalogError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested catalog resource does not exist"),
    DUPLICATE_RESOURCE("DUPLICATE_RESOURCE", "A catalog resource with the same unique value already exists"),
    INVALID_STATE("INVALID_STATE", "The catalog resource is not in a valid state for this operation"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The catalog resource was modified by another request"),
    INVALID_CURSOR("INVALID_CURSOR", "The catalog continuation cursor is invalid"),
    INVALID_MEDIA("INVALID_MEDIA", "The product media is invalid"),
    MEDIA_STORAGE_UNAVAILABLE("MEDIA_STORAGE_UNAVAILABLE", "Product media storage is temporarily unavailable"),
    CAPACITY_PROTECTION("CAPACITY_PROTECTION", "Catalog capacity protection rejected this request"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for another request"),
    REVIEW_ALREADY_SUBMITTED("REVIEW_ALREADY_SUBMITTED", "This completed order line was already reviewed"),
    REVIEW_NOT_PUBLISHED("REVIEW_NOT_PUBLISHED", "The review is not available for this action"),
    REVIEW_ACTION_NOT_ALLOWED("REVIEW_ACTION_NOT_ALLOWED", "This review action is not allowed"),
    REPORT_ALREADY_RESOLVED("REPORT_ALREADY_RESOLVED", "The review report was already resolved"),
    SEARCH_INDEX_UNAVAILABLE("SEARCH_INDEX_UNAVAILABLE", "Product search is temporarily unavailable");

    private final String code;
    private final String message;

    CatalogError(String code, String message) {
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
