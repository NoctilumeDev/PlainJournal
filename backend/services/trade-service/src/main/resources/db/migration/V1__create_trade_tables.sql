CREATE TABLE cart_item (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_user_sku UNIQUE (user_id, sku_id),
    CONSTRAINT ck_cart_quantity CHECK (quantity > 0 AND quantity <= 1000000000)
);

CREATE INDEX idx_cart_user_updated ON cart_item (user_id, updated_at);

CREATE TABLE trade_order (
    id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(32) NOT NULL,
    warehouse_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    payment_deadline TIMESTAMP(3) NOT NULL,
    close_reason VARCHAR(64) NULL,
    recovery_attempts INT NOT NULL DEFAULT 0,
    next_recovery_at TIMESTAMP(3) NULL,
    last_error VARCHAR(500) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trade_order_no UNIQUE (order_no),
    CONSTRAINT uk_trade_reservation_no UNIQUE (reservation_no),
    CONSTRAINT uk_trade_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_trade_total CHECK (total_amount >= 0),
    CONSTRAINT ck_trade_recovery_attempts CHECK (recovery_attempts >= 0)
);

CREATE INDEX idx_trade_order_user_created ON trade_order (user_id, created_at);
CREATE INDEX idx_trade_order_recovery ON trade_order (status, next_recovery_at);
CREATE INDEX idx_trade_order_payment_deadline ON trade_order (status, payment_deadline);

CREATE TABLE order_item (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_title VARCHAR(200) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(120) NOT NULL,
    spec_json VARCHAR(2000) NOT NULL,
    image_object_key VARCHAR(512) NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    quantity BIGINT NOT NULL,
    line_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_item_sku UNIQUE (order_id, sku_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_amount CHECK (unit_price >= 0 AND line_amount >= 0)
);

CREATE INDEX idx_order_item_order ON order_item (order_id);

CREATE TABLE order_status_history (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    command VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NULL,
    operator_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_history_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);

CREATE INDEX idx_order_history_order_created ON order_status_history (order_id, created_at);

CREATE TABLE outbox_event (
    id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    aggregate_version INT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claimed_at TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_trade_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_trade_outbox_publishable ON outbox_event (status, next_attempt_at);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
