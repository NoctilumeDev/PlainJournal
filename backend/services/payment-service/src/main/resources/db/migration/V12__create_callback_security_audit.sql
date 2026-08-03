CREATE TABLE callback_security_audit (
    id BIGINT NOT NULL,
    callback_type VARCHAR(16) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    claimed_external_event_id VARCHAR(100) NOT NULL,
    reference_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    raw_payload TEXT NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_callback_security_event
    ON callback_security_audit (
        callback_type,
        channel,
        claimed_external_event_id,
        received_at
    );

CREATE INDEX idx_callback_security_reference
    ON callback_security_audit (callback_type, reference_no, received_at);
