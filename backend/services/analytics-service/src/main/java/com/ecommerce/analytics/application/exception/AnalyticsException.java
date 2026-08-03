package com.ecommerce.analytics.application.exception;

public class AnalyticsException extends RuntimeException {

    private final AnalyticsError error;

    public AnalyticsException(AnalyticsError error) {
        super(error.message());
        this.error = error;
    }

    public AnalyticsException(AnalyticsError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public AnalyticsError error() {
        return error;
    }
}
