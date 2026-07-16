CREATE TABLE return_receipt (
    id BIGINT NOT NULL,
    return_receipt_no VARCHAR(64) NOT NULL,
    after_sale_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    refund_amount DECIMAL(18,2) NOT NULL,
    carrier VARCHAR(40) NULL,
    tracking_no VARCHAR(100) NULL,
    inspection_remark VARCHAR(500) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    shipped_at TIMESTAMP(3) NULL,
    received_at TIMESTAMP(3) NULL,
    inspected_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_return_receipt_no UNIQUE (return_receipt_no),
    CONSTRAINT uk_return_after_sale UNIQUE (after_sale_no),
    CONSTRAINT uk_return_tracking UNIQUE (carrier, tracking_no),
    CONSTRAINT ck_return_refund_amount CHECK (refund_amount >= 0),
    CONSTRAINT ck_return_version CHECK (version >= 0)
);

CREATE INDEX idx_return_user_created ON return_receipt (user_id, created_at);
CREATE INDEX idx_return_status_updated ON return_receipt (status, updated_at);

CREATE TABLE return_item (
    id BIGINT NOT NULL,
    return_receipt_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    refundable_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_return_item_line UNIQUE (return_receipt_id, line_no),
    CONSTRAINT fk_return_item_receipt FOREIGN KEY (return_receipt_id) REFERENCES return_receipt (id),
    CONSTRAINT ck_return_item_values CHECK (line_no > 0 AND quantity > 0 AND refundable_amount >= 0)
);

CREATE INDEX idx_return_item_sku ON return_item (sku_id, return_receipt_id);

CREATE TABLE return_status_history (
    id BIGINT NOT NULL,
    return_receipt_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    command VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NULL,
    operator_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_return_history_receipt FOREIGN KEY (return_receipt_id) REFERENCES return_receipt (id)
);

CREATE INDEX idx_return_history_created ON return_status_history (return_receipt_id, created_at);
