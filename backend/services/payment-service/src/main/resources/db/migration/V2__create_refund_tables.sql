CREATE TABLE refund_order (
    id BIGINT NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    after_sale_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    payment_id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    channel_refund_no VARCHAR(100) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    refunded_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_no UNIQUE (refund_no),
    CONSTRAINT uk_refund_after_sale UNIQUE (after_sale_no),
    CONSTRAINT uk_refund_payment UNIQUE (payment_id),
    CONSTRAINT fk_refund_payment_order FOREIGN KEY (payment_id) REFERENCES payment_order (id),
    CONSTRAINT ck_refund_amount CHECK (amount >= 0),
    CONSTRAINT ck_refund_version CHECK (version >= 0)
);

CREATE INDEX idx_refund_user_created ON refund_order (user_id, created_at);
CREATE INDEX idx_refund_status_updated ON refund_order (status, updated_at);

CREATE TABLE refund_transaction (
    id BIGINT NOT NULL,
    refund_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_refund_no VARCHAR(100) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_channel_transaction UNIQUE (channel, channel_refund_no),
    CONSTRAINT fk_refund_transaction_order FOREIGN KEY (refund_id) REFERENCES refund_order (id)
);

CREATE INDEX idx_refund_transaction_order ON refund_transaction (refund_id, created_at);

CREATE TABLE refund_callback_log (
    id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    raw_payload TEXT NOT NULL,
    error_message VARCHAR(500) NULL,
    received_at TIMESTAMP(3) NOT NULL,
    processed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_callback_event UNIQUE (channel, external_event_id)
);

CREATE INDEX idx_refund_callback_order ON refund_callback_log (refund_no, received_at);
