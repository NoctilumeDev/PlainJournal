CREATE TABLE catalog_product_create_command (
    operator_id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (operator_id, command_id),
    CONSTRAINT uk_catalog_product_create_product UNIQUE (product_id)
);
