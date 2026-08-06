package com.ecommerce.identity.interfaces.rest;

import com.ecommerce.identity.application.exception.IdentityError;
import com.ecommerce.identity.application.exception.IdentityException;
import com.ecommerce.identity.application.port.LoginLockPolicy;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityExceptionHandler {

    private final LoginLockPolicy loginLockPolicy;

    public IdentityExceptionHandler(LoginLockPolicy loginLockPolicy) {
        this.loginLockPolicy = loginLockPolicy;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentityException(IdentityException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusFor(exception.error()));
        if (exception.error() == IdentityError.LOGIN_TEMPORARILY_LOCKED) {
            response.header("Retry-After", Long.toString(loginLockPolicy.lockDuration().toSeconds()));
        }
        return response.body(ApiResponse.failure(exception.error().code(), exception.error().message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_REQUEST_BODY", "Request body is invalid"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    private HttpStatus statusFor(IdentityError error) {
        return switch (error) {
            case EMAIL_ALREADY_REGISTERED -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN -> HttpStatus.UNAUTHORIZED;
            case LOGIN_TEMPORARILY_LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
            case ACCOUNT_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            case ACCOUNT_NOT_FOUND, ADDRESS_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ADDRESS_LIMIT_REACHED, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            case INVALID_PASSWORD -> HttpStatus.BAD_REQUEST;
        };
    }
}
