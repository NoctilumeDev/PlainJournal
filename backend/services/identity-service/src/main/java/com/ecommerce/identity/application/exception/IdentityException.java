package com.ecommerce.identity.application.exception;

public class IdentityException extends RuntimeException {

    private final IdentityError error;

    public IdentityException(IdentityError error) {
        super(error.message());
        this.error = error;
    }

    public IdentityError error() {
        return error;
    }
}
