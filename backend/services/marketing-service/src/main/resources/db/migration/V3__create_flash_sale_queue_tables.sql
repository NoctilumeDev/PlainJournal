ALTER TABLE flash_sale_activity
    ADD COLUMN product_id BIGINT NULL;

CREATE INDEX idx_flash_sale_product_window
    ON flash_sale_activity (product_id, status, starts_at, ends_at);

CREATE TABLE flash_sale_admission (
    id BIGINT NOT NULL,
    request_token VARCHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    address_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    remaining_admissions INT NOT NULL,
    order_no VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    version INT NOT NULL DEFAULT 0,
    accepted_at TIMESTAMP(3) NOT NULL,
    completed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_flash_sale_admission_token UNIQUE (request_token),
    CONSTRAINT uk_flash_sale_admission_user UNIQUE (activity_no, user_id),
    CONSTRAINT uk_flash_sale_admission_order UNIQUE (order_no),
    CONSTRAINT ck_flash_sale_admission_remaining CHECK (remaining_admissions >= 0),
    CONSTRAINT ck_flash_sale_admission_version CHECK (version >= 0)
);

CREATE INDEX idx_flash_sale_admission_status_time
    ON flash_sale_admission (status, updated_at, id);

CREATE TABLE flash_sale_outbox_event (
    id CHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    aggregate_version INT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claim_owner VARCHAR(100) NULL,
    claim_until TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_flash_sale_outbox_aggregate_event UNIQUE (aggregate_id, event_type),
    CONSTRAINT ck_flash_sale_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_flash_sale_outbox_publish
    ON flash_sale_outbox_event (status, next_attempt_at, claim_until, created_at);
