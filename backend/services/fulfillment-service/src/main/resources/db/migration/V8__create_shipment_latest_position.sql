ALTER TABLE fulfillment_order
    ADD COLUMN latest_position_trace_id BIGINT NULL;

ALTER TABLE fulfillment_order
    ADD COLUMN latest_position_at TIMESTAMP(3) NULL;

CREATE TABLE shipment_latest_position (
    fulfillment_id BIGINT NOT NULL,
    fulfillment_no VARCHAR(64) NOT NULL,
    trace_id BIGINT NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    location_name VARCHAR(120) NULL,
    longitude DECIMAL(10,6) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (fulfillment_id),
    CONSTRAINT uk_shipment_latest_position_no UNIQUE (fulfillment_no),
    CONSTRAINT fk_shipment_latest_position_order
        FOREIGN KEY (fulfillment_id) REFERENCES fulfillment_order (id),
    CONSTRAINT fk_shipment_latest_position_trace
        FOREIGN KEY (trace_id) REFERENCES logistics_trace (id),
    CONSTRAINT ck_shipment_latest_position_longitude
        CHECK (longitude >= -180 AND longitude <= 180),
    CONSTRAINT ck_shipment_latest_position_latitude
        CHECK (latitude >= -90 AND latitude <= 90)
);

CREATE INDEX idx_shipment_latest_position_occurred
    ON shipment_latest_position (occurred_at);
CREATE INDEX idx_shipment_latest_position_decimal
    ON shipment_latest_position (longitude, latitude);
