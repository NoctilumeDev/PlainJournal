package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CatalogExceptionHandler {

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiResponse<Void>> handleCatalogException(CatalogException exception) {
        return ResponseEntity.status(statusFor(exception.error()))
                .body(ApiResponse.failure(exception.error().code(), exception.error().message()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation() {
        CatalogError error = CatalogError.DUPLICATE_RESOURCE;
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(error.code(), error.message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .orElse("Request validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_REQUEST_BODY", "Request body is invalid"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_ERROR", exception.getMessage()));
    }

    private HttpStatus statusFor(CatalogError error) {
        return switch (error) {
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_RESOURCE, INVALID_STATE, CONCURRENT_MODIFICATION,
                    IDEMPOTENCY_CONFLICT, REVIEW_ALREADY_SUBMITTED,
                    REVIEW_NOT_PUBLISHED, REVIEW_ACTION_NOT_ALLOWED,
                    REPORT_ALREADY_RESOLVED -> HttpStatus.CONFLICT;
            case INVALID_CURSOR, INVALID_MEDIA -> HttpStatus.BAD_REQUEST;
            case MEDIA_STORAGE_UNAVAILABLE, CAPACITY_PROTECTION,
                    SEARCH_INDEX_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
