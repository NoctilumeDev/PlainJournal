CREATE TABLE analytics_projection_guard (
    id INT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_analytics_projection_guard_id CHECK (id = 1)
);

INSERT INTO analytics_projection_guard (id, updated_at)
VALUES (1, CURRENT_TIMESTAMP);

CREATE TABLE analytics_source_event (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    producer VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    business_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_analytics_source_logical UNIQUE
        (producer, aggregate_type, aggregate_id, aggregate_version, event_type),
    CONSTRAINT ck_analytics_source_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_analytics_source_user CHECK (user_id > 0),
    CONSTRAINT ck_analytics_source_amount CHECK (amount >= 0)
);

CREATE INDEX idx_analytics_source_business_date
    ON analytics_source_event (business_date, event_type);
CREATE INDEX idx_analytics_source_order
    ON analytics_source_event (order_no, occurred_at);

CREATE TABLE analytics_source_product_line (
    event_id VARCHAR(64) NOT NULL,
    line_no INT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_title VARCHAR(255) NOT NULL,
    sku_code VARCHAR(80) NOT NULL,
    quantity BIGINT NOT NULL,
    payable_amount DECIMAL(19, 2) NULL,
    PRIMARY KEY (event_id, line_no),
    CONSTRAINT fk_analytics_source_product_event
        FOREIGN KEY (event_id) REFERENCES analytics_source_event (event_id),
    CONSTRAINT ck_analytics_source_product_line CHECK (line_no > 0),
    CONSTRAINT ck_analytics_source_product_id CHECK (product_id > 0),
    CONSTRAINT ck_analytics_source_sku_id CHECK (sku_id > 0),
    CONSTRAINT ck_analytics_source_product_quantity CHECK (quantity > 0),
    CONSTRAINT ck_analytics_source_product_amount
        CHECK (payable_amount IS NULL OR payable_amount >= 0)
);

CREATE INDEX idx_analytics_source_product
    ON analytics_source_product_line (product_id, event_id);

CREATE TABLE analytics_daily_summary (
    business_date DATE NOT NULL,
    created_order_count BIGINT NOT NULL DEFAULT 0,
    created_order_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    payment_count BIGINT NOT NULL DEFAULT 0,
    payment_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    completed_order_count BIGINT NOT NULL DEFAULT 0,
    completed_order_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    closed_order_count BIGINT NOT NULL DEFAULT 0,
    after_sale_count BIGINT NOT NULL DEFAULT 0,
    after_sale_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    refund_count BIGINT NOT NULL DEFAULT 0,
    refund_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (business_date),
    CONSTRAINT ck_analytics_daily_counts CHECK (
        created_order_count >= 0
        AND payment_count >= 0
        AND completed_order_count >= 0
        AND closed_order_count >= 0
        AND after_sale_count >= 0
        AND refund_count >= 0
    ),
    CONSTRAINT ck_analytics_daily_amounts CHECK (
        created_order_amount >= 0
        AND payment_amount >= 0
        AND completed_order_amount >= 0
        AND after_sale_amount >= 0
        AND refund_amount >= 0
    )
);

CREATE TABLE analytics_product_summary (
    business_date DATE NOT NULL,
    product_id BIGINT NOT NULL,
    product_title VARCHAR(255) NOT NULL,
    completed_order_count BIGINT NOT NULL,
    units_sold BIGINT NOT NULL,
    net_revenue DECIMAL(19, 2) NOT NULL,
    revenue_covered_order_count BIGINT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (business_date, product_id),
    CONSTRAINT ck_analytics_product_counts CHECK (
        completed_order_count >= 0
        AND units_sold >= 0
        AND revenue_covered_order_count >= 0
        AND revenue_covered_order_count <= completed_order_count
    ),
    CONSTRAINT ck_analytics_product_revenue CHECK (net_revenue >= 0)
);

CREATE INDEX idx_analytics_product_revenue
    ON analytics_product_summary (business_date, net_revenue, product_id);

CREATE TABLE analytics_rebuild_audit (
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    source_event_count BIGINT NOT NULL,
    before_issue_count BIGINT NOT NULL,
    after_issue_count BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (command_id),
    CONSTRAINT ck_analytics_rebuild_operator CHECK (operator_id > 0),
    CONSTRAINT ck_analytics_rebuild_counts CHECK (
        source_event_count >= 0
        AND before_issue_count >= 0
        AND after_issue_count >= 0
    )
);

CREATE INDEX idx_analytics_rebuild_created
    ON analytics_rebuild_audit (created_at, command_id);

CREATE TABLE consumer_failure (
    message_id VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    raw_payload TEXT NOT NULL,
    attempts INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_error VARCHAR(1000) NOT NULL,
    first_failed_at TIMESTAMP(3) NOT NULL,
    last_failed_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    PRIMARY KEY (message_id, consumer_group),
    CONSTRAINT ck_analytics_consumer_failure_attempts CHECK (attempts > 0)
);

CREATE INDEX idx_analytics_consumer_failure_status
    ON consumer_failure (status, last_failed_at);
