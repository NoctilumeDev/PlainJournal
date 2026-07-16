package com.ecommerce.payment.application.exception;

public class PaymentException extends RuntimeException {

    private final PaymentError error;

    public PaymentException(PaymentError error) {
        super(error.message());
        this.error = error;
    }

    public PaymentException(PaymentError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public PaymentError error() {
        return error;
    }
}
