package com.ecommerce.catalog.application.exception;

public class CatalogException extends RuntimeException {

    private final CatalogError error;

    public CatalogException(CatalogError error) {
        super(error.message());
        this.error = error;
    }

    public CatalogException(CatalogError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public CatalogError error() {
        return error;
    }
}
