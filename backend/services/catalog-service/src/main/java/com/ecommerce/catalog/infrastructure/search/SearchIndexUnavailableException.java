package com.ecommerce.catalog.infrastructure.search;

public class SearchIndexUnavailableException extends RuntimeException {

    public SearchIndexUnavailableException(String message) {
        super(message);
    }

    public SearchIndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
