package com.ecommerce.trade.domain;

public enum OrderStatus {
    PENDING_STOCK,
    PENDING_PAYMENT,
    CANCELING,
    CANCELED,
    CLOSED,
    PAID,
    FULFILLING,
    SHIPPED,
    COMPLETED,
    PAYMENT_EXCEPTION
}
