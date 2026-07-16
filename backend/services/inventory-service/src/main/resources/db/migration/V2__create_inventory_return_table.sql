CREATE TABLE inventory_return (
    id BIGINT NOT NULL,
    after_sale_no VARCHAR(64) NOT NULL,
    return_receipt_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    stocked_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_return_after_sale UNIQUE (after_sale_no),
    CONSTRAINT uk_inventory_return_receipt UNIQUE (return_receipt_no),
    CONSTRAINT fk_inventory_return_reservation FOREIGN KEY (reservation_no)
        REFERENCES inventory_reservation (reservation_no)
);

CREATE INDEX idx_inventory_return_status_created ON inventory_return (status, created_at);
