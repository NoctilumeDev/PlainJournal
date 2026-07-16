CREATE TABLE payment_order (
    id BIGINT NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    channel_transaction_no VARCHAR(100) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    paid_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_no UNIQUE (payment_no),
    CONSTRAINT uk_payment_order_no UNIQUE (order_no),
    CONSTRAINT uk_payment_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0)
);

CREATE INDEX idx_payment_user_created ON payment_order (user_id, created_at);
CREATE INDEX idx_payment_status_updated ON payment_order (status, updated_at);

CREATE TABLE payment_transaction (
    id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_transaction_no VARCHAR(100) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_channel_transaction UNIQUE (channel, channel_transaction_no),
    CONSTRAINT fk_payment_transaction_order FOREIGN KEY (payment_id) REFERENCES payment_order (id)
);

CREATE INDEX idx_payment_transaction_payment ON payment_transaction (payment_id, created_at);

CREATE TABLE payment_callback_log (
    id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    raw_payload TEXT NOT NULL,
    error_message VARCHAR(500) NULL,
    received_at TIMESTAMP(3) NOT NULL,
    processed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_callback_event UNIQUE (channel, external_event_id)
);

CREATE INDEX idx_payment_callback_payment ON payment_callback_log (payment_no, received_at);

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
    CONSTRAINT ck_payment_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_payment_outbox_publishable ON outbox_event (status, next_attempt_at);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
