package com.ecommerce.marketing.application.port;

public class FlashSaleAdmissionStoreException extends RuntimeException {

    public FlashSaleAdmissionStoreException(String message) {
        super(message);
    }

    public FlashSaleAdmissionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
