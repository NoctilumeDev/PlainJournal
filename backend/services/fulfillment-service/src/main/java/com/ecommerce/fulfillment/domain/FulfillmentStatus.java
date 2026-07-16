package com.ecommerce.fulfillment.domain;

public enum FulfillmentStatus {
    CREATED,
    PICKING,
    PACKED,
    SHIPPED,
    IN_TRANSIT,
    DELIVERING,
    SIGNED,
    CANCELED,
    EXCEPTION
}
