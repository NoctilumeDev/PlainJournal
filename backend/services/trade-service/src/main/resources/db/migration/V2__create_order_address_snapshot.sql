CREATE TABLE order_address_snapshot (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    source_address_id BIGINT NOT NULL,
    recipient_name VARCHAR(60) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    province VARCHAR(60) NOT NULL,
    city VARCHAR(60) NOT NULL,
    district VARCHAR(60) NOT NULL,
    detail_address VARCHAR(240) NOT NULL,
    postal_code VARCHAR(20) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_address_order UNIQUE (order_id),
    CONSTRAINT fk_order_address_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);
