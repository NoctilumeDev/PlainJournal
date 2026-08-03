package com.ecommerce.analytics.interfaces.rest;

import com.ecommerce.analytics.application.exception.AnalyticsError;
import com.ecommerce.analytics.application.exception.AnalyticsException;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AnalyticsExceptionHandler {

    @ExceptionHandler(AnalyticsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAnalyticsException(
            AnalyticsException exception) {
        return ResponseEntity.status(statusFor(exception.error()))
                .body(ApiResponse.failure(
                        exception.error().code(),
                        exception.error().message()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        String message = exception.getMessage() == null
                ? "Request validation failed"
                : exception.getMessage();
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    private HttpStatus statusFor(AnalyticsError error) {
        return switch (error) {
            case INVALID_DATE_RANGE -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case RECONCILIATION_SATURATED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REBUILD_DID_NOT_CONVERGE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
