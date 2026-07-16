package com.ecommerce.marketing.application.exception;

public class MarketingException extends RuntimeException {

    private final MarketingError error;

    public MarketingException(MarketingError error) {
        super(error.message());
        this.error = error;
    }

    public MarketingException(MarketingError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public MarketingError error() {
        return error;
    }
}
