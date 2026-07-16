package com.ecommerce.trade.application.exception;

public class TradeException extends RuntimeException {

    private final TradeError error;

    public TradeException(TradeError error) {
        super(error.message());
        this.error = error;
    }

    public TradeException(TradeError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public TradeError error() {
        return error;
    }
}
