package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
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
public class MarketingExceptionHandler {

    @ExceptionHandler(MarketingException.class)
    public ResponseEntity<ApiResponse<Void>> handleMarketingException(MarketingException exception) {
        return ResponseEntity.status(statusFor(exception.error()))
                .body(ApiResponse.failure(exception.error().code(), exception.error().message()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("DUPLICATE_RESOURCE", "A unique marketing value already exists"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException methodArgument
                ? methodArgument.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed")
                : "Request validation failed";
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("INVALID_REQUEST_BODY", "Request body is invalid"));
    }

    private HttpStatus statusFor(MarketingError error) {
        return switch (error) {
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_RULE, INVALID_PRICING_REQUEST, INVALID_FLASH_SALE -> HttpStatus.BAD_REQUEST;
            case FLASH_SALE_NOT_READY, FLASH_SALE_ADMISSION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case FLASH_SALE_ENDED -> HttpStatus.GONE;
            case BENEFIT_NOT_ELIGIBLE, DUPLICATE_BENEFIT_TYPE, IDEMPOTENCY_CONFLICT,
                    INVALID_STATE, CONCURRENT_MODIFICATION, FLASH_SALE_INVALID_STATE,
                    FLASH_SALE_NOT_STARTED, FLASH_SALE_SOLD_OUT -> HttpStatus.CONFLICT;
        };
    }
}
