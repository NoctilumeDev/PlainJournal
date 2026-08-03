package com.ecommerce.chat.application.exception;

public enum ChatError {
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", "The requested conversation does not exist"),
    MESSAGE_NOT_FOUND("MESSAGE_NOT_FOUND", "The requested chat message does not exist"),
    CONVERSATION_ACCESS_DENIED("CONVERSATION_ACCESS_DENIED", "The conversation is not accessible to this user"),
    CONVERSATION_CLOSED("CONVERSATION_CLOSED", "The conversation is closed"),
    CONVERSATION_ALREADY_ASSIGNED("CONVERSATION_ALREADY_ASSIGNED", "The conversation is assigned to another agent"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was already used with different content"),
    INVALID_CONTEXT("INVALID_CONTEXT", "Conversation context type and context ID must be provided together"),
    INVALID_MESSAGE_TYPE("INVALID_MESSAGE_TYPE", "The message type is not supported"),
    INVALID_MESSAGE_CONTENT("INVALID_MESSAGE_CONTENT", "The message content does not match its type"),
    INVALID_ATTACHMENT("INVALID_ATTACHMENT", "The attachment request is invalid"),
    ATTACHMENT_NOT_FOUND("ATTACHMENT_NOT_FOUND", "The requested attachment does not exist"),
    ATTACHMENT_UPLOAD_EXPIRED("ATTACHMENT_UPLOAD_EXPIRED", "The attachment upload intent has expired"),
    ATTACHMENT_NOT_READY("ATTACHMENT_NOT_READY", "The attachment has not been confirmed"),
    ATTACHMENT_ALREADY_ATTACHED("ATTACHMENT_ALREADY_ATTACHED", "The attachment is already bound to a message"),
    ATTACHMENT_OBJECT_MISSING("ATTACHMENT_OBJECT_MISSING", "The attachment object has not been uploaded"),
    ATTACHMENT_OBJECT_MISMATCH("ATTACHMENT_OBJECT_MISMATCH", "The uploaded object does not match the declared attachment"),
    ATTACHMENT_STORAGE_UNAVAILABLE("ATTACHMENT_STORAGE_UNAVAILABLE", "Attachment storage is unavailable"),
    ATTACHMENT_INFECTED("ATTACHMENT_INFECTED", "The attachment failed malware scanning"),
    ATTACHMENT_SCAN_RETRY_NOT_ALLOWED(
            "ATTACHMENT_SCAN_RETRY_NOT_ALLOWED",
            "The attachment scan cannot be retried from its current state"),
    WEBSOCKET_TICKET_ACCESS_DENIED("WEBSOCKET_TICKET_ACCESS_DENIED", "WebSocket ticket access is denied"),
    CHAT_REALTIME_UNAVAILABLE("CHAT_REALTIME_UNAVAILABLE", "Chat realtime authentication is unavailable");

    private final String code;
    private final String message;

    ChatError(String code, String message) {
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
