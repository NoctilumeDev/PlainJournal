CREATE TABLE fulfillment_order (
    id BIGINT NOT NULL,
    fulfillment_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    carrier VARCHAR(40) NULL,
    tracking_no VARCHAR(100) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    picked_at TIMESTAMP(3) NULL,
    packed_at TIMESTAMP(3) NULL,
    shipped_at TIMESTAMP(3) NULL,
    signed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fulfillment_no UNIQUE (fulfillment_no),
    CONSTRAINT uk_fulfillment_order_no UNIQUE (order_no),
    CONSTRAINT uk_fulfillment_tracking UNIQUE (carrier, tracking_no),
    CONSTRAINT ck_fulfillment_version CHECK (version >= 0)
);

CREATE INDEX idx_fulfillment_user_created ON fulfillment_order (user_id, created_at);
CREATE INDEX idx_fulfillment_status_updated ON fulfillment_order (status, updated_at);

CREATE TABLE fulfillment_status_history (
    id BIGINT NOT NULL,
    fulfillment_id BIGINT NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    command VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NULL,
    operator_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fulfillment_history_order FOREIGN KEY (fulfillment_id) REFERENCES fulfillment_order (id)
);

CREATE INDEX idx_fulfillment_history_created ON fulfillment_status_history (fulfillment_id, created_at);

CREATE TABLE logistics_trace (
    id BIGINT NOT NULL,
    fulfillment_id BIGINT NOT NULL,
    carrier VARCHAR(40) NOT NULL,
    tracking_no VARCHAR(100) NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    description VARCHAR(240) NOT NULL,
    location_name VARCHAR(120) NULL,
    longitude DECIMAL(10,6) NULL,
    latitude DECIMAL(9,6) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_logistics_external_event UNIQUE (carrier, tracking_no, external_event_id),
    CONSTRAINT fk_logistics_trace_order FOREIGN KEY (fulfillment_id) REFERENCES fulfillment_order (id),
    CONSTRAINT ck_logistics_longitude CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180)),
    CONSTRAINT ck_logistics_latitude CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90))
);

CREATE INDEX idx_logistics_trace_timeline ON logistics_trace (fulfillment_id, occurred_at);

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
    CONSTRAINT ck_fulfillment_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_fulfillment_outbox_publishable ON outbox_event (status, next_attempt_at);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
