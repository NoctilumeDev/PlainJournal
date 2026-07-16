package com.ecommerce.catalog.application.exception;

public enum CatalogError {
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "The requested catalog resource does not exist"),
    DUPLICATE_RESOURCE("DUPLICATE_RESOURCE", "A catalog resource with the same unique value already exists"),
    INVALID_STATE("INVALID_STATE", "The catalog resource is not in a valid state for this operation"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The catalog resource was modified by another request"),
    INVALID_MEDIA("INVALID_MEDIA", "The product media is invalid"),
    MEDIA_STORAGE_UNAVAILABLE("MEDIA_STORAGE_UNAVAILABLE", "Product media storage is temporarily unavailable");

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
