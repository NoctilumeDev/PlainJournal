CREATE TABLE payment_exception_refund_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NULL,
    refund_no VARCHAR(64) NULL,
    operator_id VARCHAR(64) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_exception_refund_command UNIQUE (command_id),
    CONSTRAINT ck_payment_exception_refund_outcome
        CHECK (outcome IN ('PROCESSING', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_payment_exception_refund_payment_created
    ON payment_exception_refund_audit (payment_no, created_at);
