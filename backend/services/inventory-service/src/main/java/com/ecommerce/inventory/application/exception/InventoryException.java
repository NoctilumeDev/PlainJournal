package com.ecommerce.inventory.application.exception;

public class InventoryException extends RuntimeException {

    private final InventoryError error;

    public InventoryException(InventoryError error) {
        super(error.message());
        this.error = error;
    }

    public InventoryError error() {
        return error;
    }
}
