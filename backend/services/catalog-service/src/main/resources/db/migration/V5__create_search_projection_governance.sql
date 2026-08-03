ALTER TABLE product_spu
    ADD COLUMN search_revision BIGINT NOT NULL DEFAULT 1;

CREATE TABLE catalog_search_outbox (
    id VARCHAR(36) NOT NULL,
    product_id BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claimed_at TIMESTAMP(3) NULL,
    claim_owner VARCHAR(100) NULL,
    claim_until TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_catalog_search_outbox_revision CHECK (target_revision > 0),
    CONSTRAINT ck_catalog_search_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_catalog_search_outbox_status CHECK (
        status IN ('PENDING', 'PROJECTING', 'PUBLISHED', 'NEEDS_ATTENTION'))
);

CREATE INDEX idx_catalog_search_outbox_dispatch
    ON catalog_search_outbox (status, next_attempt_at, created_at);
CREATE INDEX idx_catalog_search_outbox_product
    ON catalog_search_outbox (product_id, created_at);

CREATE TABLE catalog_search_recovery_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    outbox_id VARCHAR(36) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status_before VARCHAR(24) NOT NULL,
    status_after VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_search_recovery_command UNIQUE (command_id),
    CONSTRAINT fk_catalog_search_recovery_outbox
        FOREIGN KEY (outbox_id) REFERENCES catalog_search_outbox (id)
);

CREATE INDEX idx_catalog_search_recovery_outbox
    ON catalog_search_recovery_audit (outbox_id, created_at);

CREATE TABLE catalog_search_rebuild (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    target_index VARCHAR(160) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    indexed_count BIGINT NOT NULL DEFAULT 0,
    claimed_at TIMESTAMP(3) NULL,
    claim_owner VARCHAR(100) NULL,
    claim_until TIMESTAMP(3) NULL,
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_search_rebuild_command UNIQUE (command_id),
    CONSTRAINT ck_catalog_search_rebuild_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_catalog_search_rebuild_count CHECK (indexed_count >= 0),
    CONSTRAINT ck_catalog_search_rebuild_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'NEEDS_ATTENTION'))
);

CREATE INDEX idx_catalog_search_rebuild_dispatch
    ON catalog_search_rebuild (status, created_at);

CREATE TABLE catalog_search_rebuild_recovery_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    rebuild_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status_before VARCHAR(24) NOT NULL,
    status_after VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_search_rebuild_recovery_command UNIQUE (command_id),
    CONSTRAINT fk_catalog_search_rebuild_recovery
        FOREIGN KEY (rebuild_id) REFERENCES catalog_search_rebuild (id)
);

CREATE INDEX idx_catalog_search_rebuild_recovery
    ON catalog_search_rebuild_recovery_audit (rebuild_id, created_at);

CREATE TABLE catalog_search_reconciliation (
    id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    issue_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    mysql_revision BIGINT NULL,
    index_revision BIGINT NULL,
    occurrences INT NOT NULL,
    first_detected_at TIMESTAMP(3) NOT NULL,
    last_detected_at TIMESTAMP(3) NOT NULL,
    resolved_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_search_reconciliation UNIQUE (product_id, issue_type),
    CONSTRAINT ck_catalog_search_reconciliation_status CHECK (
        status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_catalog_search_reconciliation_issue CHECK (
        issue_type IN ('MISSING', 'STALE', 'ORPHAN')),
    CONSTRAINT ck_catalog_search_reconciliation_occurrences CHECK (occurrences > 0)
);

CREATE INDEX idx_catalog_search_reconciliation_status
    ON catalog_search_reconciliation (status, last_detected_at);
