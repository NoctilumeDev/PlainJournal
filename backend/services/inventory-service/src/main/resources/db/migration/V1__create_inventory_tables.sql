CREATE TABLE warehouse (
    id BIGINT NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_warehouse_code UNIQUE (code)
);

CREATE TABLE inventory_balance (
    id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    on_hand BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_balance_warehouse_sku UNIQUE (warehouse_id, sku_id),
    CONSTRAINT fk_inventory_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT ck_inventory_balance_non_negative CHECK (on_hand >= 0 AND reserved >= 0),
    CONSTRAINT ck_inventory_balance_reserved CHECK (reserved <= on_hand)
);

CREATE INDEX idx_inventory_balance_sku ON inventory_balance (sku_id, warehouse_id);

CREATE TABLE stock_adjustment (
    id BIGINT NOT NULL,
    movement_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity_delta BIGINT NOT NULL,
    reason VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_adjustment_movement_no UNIQUE (movement_no),
    CONSTRAINT fk_stock_adjustment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE inventory_reservation (
    id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_no UNIQUE (reservation_no),
    CONSTRAINT fk_inventory_reservation_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE INDEX idx_inventory_reservation_expiry ON inventory_reservation (status, expires_at);
CREATE INDEX idx_inventory_reservation_order ON inventory_reservation (order_no);

CREATE TABLE inventory_reservation_item (
    id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_item UNIQUE (reservation_id, sku_id),
    CONSTRAINT fk_inventory_reservation_item_header FOREIGN KEY (reservation_id) REFERENCES inventory_reservation (id),
    CONSTRAINT ck_inventory_reservation_item_quantity CHECK (quantity > 0)
);

CREATE TABLE stock_movement (
    id BIGINT NOT NULL,
    movement_no VARCHAR(100) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    reservation_no VARCHAR(64) NULL,
    movement_type VARCHAR(30) NOT NULL,
    quantity_delta BIGINT NOT NULL,
    on_hand_after BIGINT NOT NULL,
    reserved_after BIGINT NOT NULL,
    reason VARCHAR(240) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_movement_no UNIQUE (movement_no),
    CONSTRAINT fk_stock_movement_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE INDEX idx_stock_movement_sku_time ON stock_movement (sku_id, created_at);
CREATE INDEX idx_stock_movement_reservation ON stock_movement (reservation_no, created_at);

CREATE TABLE outbox_event (
    id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    aggregate_version INT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    last_error VARCHAR(500) NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_event_publish ON outbox_event (status, next_attempt_at, id);

CREATE TABLE consumed_event (
    event_id CHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
