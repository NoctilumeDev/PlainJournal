package com.ecommerce.chat.application.exception;

public class ChatException extends RuntimeException {

    private final ChatError error;

    public ChatException(ChatError error) {
        super(error.message());
        this.error = error;
    }

    public ChatException(ChatError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public ChatError error() {
        return error;
    }
}
