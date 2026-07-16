package com.ecommerce.identity.application.exception;

public enum IdentityError {
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED", "This email is already registered"),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "Email or password is incorrect"),
    LOGIN_TEMPORARILY_LOCKED("LOGIN_TEMPORARILY_LOCKED", "Too many failed login attempts; try again later"),
    ACCOUNT_UNAVAILABLE("ACCOUNT_UNAVAILABLE", "This account is unavailable"),
    INVALID_REFRESH_TOKEN("INVALID_REFRESH_TOKEN", "The refresh token is invalid or expired"),
    ACCOUNT_NOT_FOUND("ACCOUNT_NOT_FOUND", "The account does not exist"),
    INVALID_PASSWORD("INVALID_PASSWORD", "Password must be at most 72 UTF-8 bytes"),
    ADDRESS_NOT_FOUND("ADDRESS_NOT_FOUND", "The address does not exist or does not belong to the user"),
    ADDRESS_LIMIT_REACHED("ADDRESS_LIMIT_REACHED", "A user can store at most 20 addresses"),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", "The address was modified by another request");

    private final String code;
    private final String message;

    IdentityError(String code, String message) {
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
