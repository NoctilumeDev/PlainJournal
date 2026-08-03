package com.ecommerce.notification.application.exception;

public class NotificationException extends RuntimeException {

    private final NotificationError error;

    public NotificationException(NotificationError error) {
        super(error.message());
        this.error = error;
    }

    public NotificationError error() {
        return error;
    }
}
