CREATE TABLE flash_sale_activity (
    id BIGINT NOT NULL,
    activity_no VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    sku_id BIGINT NOT NULL,
    sale_price DECIMAL(18,2) NOT NULL,
    admission_limit INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    starts_at TIMESTAMP(3) NOT NULL,
    ends_at TIMESTAMP(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_flash_sale_activity_no UNIQUE (activity_no),
    CONSTRAINT ck_flash_sale_price CHECK (sale_price > 0),
    CONSTRAINT ck_flash_sale_admission_limit CHECK (admission_limit > 0),
    CONSTRAINT ck_flash_sale_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_flash_sale_version CHECK (version >= 0)
);

CREATE INDEX idx_flash_sale_status_window
    ON flash_sale_activity (status, starts_at, ends_at);
CREATE INDEX idx_flash_sale_sku_window
    ON flash_sale_activity (sku_id, starts_at, ends_at);
