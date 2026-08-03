CREATE TABLE refund_dispatch_retry_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    error_code VARCHAR(64) NULL,
    before_refund_status VARCHAR(32) NULL,
    before_request_status VARCHAR(24) NULL,
    before_request_attempts INT NULL,
    before_last_error VARCHAR(500) NULL,
    after_refund_status VARCHAR(32) NULL,
    after_request_status VARCHAR(24) NULL,
    after_request_attempts INT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refund_dispatch_retry_command UNIQUE (command_id),
    CONSTRAINT ck_refund_dispatch_retry_outcome CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_refund_dispatch_retry_before_attempts
        CHECK (before_request_attempts IS NULL OR before_request_attempts >= 0),
    CONSTRAINT ck_refund_dispatch_retry_after_attempts
        CHECK (after_request_attempts IS NULL OR after_request_attempts >= 0)
);

CREATE INDEX idx_refund_dispatch_retry_refund_created
    ON refund_dispatch_retry_audit (refund_no, created_at);
