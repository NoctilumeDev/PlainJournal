package com.ecommerce.notification.interfaces.rest;

import com.ecommerce.notification.application.exception.NotificationError;
import com.ecommerce.notification.application.exception.NotificationException;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotificationException(
            NotificationException exception) {
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

    private HttpStatus statusFor(NotificationError error) {
        return switch (error) {
            case NOTIFICATION_NOT_FOUND, DELIVERY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DELIVERY_RETRY_NOT_ALLOWED, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_CURSOR -> HttpStatus.BAD_REQUEST;
        };
    }
}
