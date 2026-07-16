package com.ecommerce.fulfillment.application.exception;

public class FulfillmentException extends RuntimeException {

    private final FulfillmentError error;

    public FulfillmentException(FulfillmentError error) {
        super(error.message());
        this.error = error;
    }

    public FulfillmentError error() {
        return error;
    }
}
