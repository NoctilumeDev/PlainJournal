CREATE TABLE fulfillment_exception_resolution (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    fulfillment_id BIGINT NOT NULL,
    fulfillment_no VARCHAR(64) NOT NULL,
    resume_status VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fulfillment_exception_resolution_command UNIQUE (command_id),
    CONSTRAINT fk_fulfillment_exception_resolution_order
        FOREIGN KEY (fulfillment_id) REFERENCES fulfillment_order (id),
    CONSTRAINT ck_fulfillment_exception_resolution_status
        CHECK (resume_status IN ('PICKING', 'SHIPPED', 'IN_TRANSIT', 'DELIVERING'))
);

CREATE INDEX idx_fulfillment_exception_resolution_order_created
    ON fulfillment_exception_resolution (fulfillment_id, created_at);
