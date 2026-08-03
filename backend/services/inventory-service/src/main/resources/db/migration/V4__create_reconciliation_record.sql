CREATE TABLE reconciliation_record (
    id BIGINT NOT NULL,
    domain VARCHAR(32) NOT NULL,
    reference_no VARCHAR(64) NOT NULL,
    issue_type VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurrences INT NOT NULL,
    first_detected_at TIMESTAMP(3) NOT NULL,
    last_detected_at TIMESTAMP(3) NOT NULL,
    resolved_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reconciliation_issue UNIQUE (domain, reference_no, issue_type),
    CONSTRAINT ck_inventory_reconciliation_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_inventory_reconciliation_occurrences CHECK (occurrences > 0)
);

CREATE INDEX idx_inventory_reconciliation_status_detected
    ON reconciliation_record (status, last_detected_at);

CREATE INDEX idx_inventory_outbox_aggregate_event
    ON outbox_event (aggregate_id, event_type);
