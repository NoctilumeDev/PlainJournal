package com.ecommerce.chat.interfaces.rest;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
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
public class ChatExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatException(ChatException exception) {
        return ResponseEntity.status(statusFor(exception.error()))
                .body(ApiResponse.failure(exception.error().code(), exception.error().message()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation() {
        ChatError error = ChatError.IDEMPOTENCY_CONFLICT;
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

    private HttpStatus statusFor(ChatError error) {
        return switch (error) {
            case CONVERSATION_NOT_FOUND, MESSAGE_NOT_FOUND, ATTACHMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONVERSATION_ACCESS_DENIED, WEBSOCKET_TICKET_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case ATTACHMENT_UPLOAD_EXPIRED -> HttpStatus.GONE;
            case ATTACHMENT_OBJECT_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ATTACHMENT_STORAGE_UNAVAILABLE, CHAT_REALTIME_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case CONVERSATION_CLOSED, CONVERSATION_ALREADY_ASSIGNED, IDEMPOTENCY_CONFLICT,
                    ATTACHMENT_NOT_READY, ATTACHMENT_ALREADY_ATTACHED, ATTACHMENT_OBJECT_MISSING,
                    ATTACHMENT_INFECTED, ATTACHMENT_SCAN_RETRY_NOT_ALLOWED ->
                    HttpStatus.CONFLICT;
            case INVALID_CONTEXT, INVALID_MESSAGE_TYPE, INVALID_MESSAGE_CONTENT, INVALID_ATTACHMENT ->
                    HttpStatus.BAD_REQUEST;
        };
    }
}
