CREATE TABLE after_sale_order (
    id BIGINT NOT NULL,
    after_sale_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    after_sale_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    review_reason VARCHAR(500) NULL,
    refund_amount DECIMAL(18,2) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    return_receipt_no VARCHAR(64) NULL,
    refund_no VARCHAR(64) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    approved_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_after_sale_no UNIQUE (after_sale_no),
    CONSTRAINT uk_after_sale_order UNIQUE (order_id),
    CONSTRAINT uk_after_sale_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_after_sale_trade_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT ck_after_sale_refund_amount CHECK (refund_amount >= 0),
    CONSTRAINT ck_after_sale_version CHECK (version >= 0)
);

CREATE INDEX idx_after_sale_user_created ON after_sale_order (user_id, created_at);
CREATE INDEX idx_after_sale_status_updated ON after_sale_order (status, updated_at);

CREATE TABLE after_sale_item (
    id BIGINT NOT NULL,
    after_sale_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_title VARCHAR(200) NOT NULL,
    sku_name VARCHAR(120) NOT NULL,
    quantity BIGINT NOT NULL,
    line_amount DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    refundable_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_after_sale_item_line UNIQUE (after_sale_id, line_no),
    CONSTRAINT fk_after_sale_item_order FOREIGN KEY (after_sale_id) REFERENCES after_sale_order (id),
    CONSTRAINT fk_after_sale_source_item FOREIGN KEY (order_item_id) REFERENCES order_item (id),
    CONSTRAINT ck_after_sale_item_values CHECK (
        line_no > 0 AND quantity > 0 AND line_amount >= 0
        AND discount_amount >= 0 AND refundable_amount >= 0
        AND line_amount = discount_amount + refundable_amount
    )
);

CREATE INDEX idx_after_sale_item_sku ON after_sale_item (sku_id, after_sale_id);

CREATE TABLE after_sale_history (
    id BIGINT NOT NULL,
    after_sale_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    command VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NULL,
    operator_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_after_sale_history_order FOREIGN KEY (after_sale_id) REFERENCES after_sale_order (id)
);

CREATE INDEX idx_after_sale_history_created ON after_sale_history (after_sale_id, created_at);
