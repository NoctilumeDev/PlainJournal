ALTER TABLE trade_order
    ADD COLUMN order_source VARCHAR(24) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE trade_order
    ADD COLUMN source_reference VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_trade_order_source_reference
    ON trade_order (source_reference);

ALTER TABLE outbox_event
    ADD COLUMN destination_topic VARCHAR(128) NULL;

CREATE TABLE flash_sale_order_request (
    id BIGINT NOT NULL,
    request_token VARCHAR(64) NOT NULL,
    admission_event_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    address_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    sale_price DECIMAL(18,2) NOT NULL,
    status VARCHAR(24) NOT NULL,
    order_no VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    last_error VARCHAR(500) NULL,
    version INT NOT NULL DEFAULT 0,
    accepted_at TIMESTAMP(3) NOT NULL,
    activity_ends_at TIMESTAMP(3) NOT NULL,
    completed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_flash_sale_order_request_token UNIQUE (request_token),
    CONSTRAINT uk_flash_sale_order_admission_event UNIQUE (admission_event_id),
    CONSTRAINT uk_flash_sale_order_order_no UNIQUE (order_no),
    CONSTRAINT ck_flash_sale_order_price CHECK (sale_price > 0),
    CONSTRAINT ck_flash_sale_order_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_flash_sale_order_version CHECK (version >= 0)
);

CREATE INDEX idx_flash_sale_order_request_recovery
    ON flash_sale_order_request (status, next_attempt_at, id);
