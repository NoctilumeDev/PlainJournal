CREATE TABLE catalog_category (
    id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(80) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_category_slug UNIQUE (slug),
    CONSTRAINT fk_catalog_category_parent FOREIGN KEY (parent_id) REFERENCES catalog_category (id)
);

CREATE INDEX idx_catalog_category_parent_status ON catalog_category (parent_id, status, sort_order);

CREATE TABLE catalog_brand (
    id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    logo_object_key VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_catalog_brand_slug UNIQUE (slug)
);

CREATE INDEX idx_catalog_brand_status_name ON catalog_brand (status, name);

CREATE TABLE product_spu (
    id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    subtitle VARCHAR(240) NULL,
    description TEXT NULL,
    status VARCHAR(20) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_spu_category FOREIGN KEY (category_id) REFERENCES catalog_category (id),
    CONSTRAINT fk_product_spu_brand FOREIGN KEY (brand_id) REFERENCES catalog_brand (id)
);

CREATE INDEX idx_product_spu_public ON product_spu (status, category_id, brand_id, created_at);

CREATE TABLE product_sku (
    id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    spec_json VARCHAR(2000) NOT NULL,
    sale_price DECIMAL(18, 2) NOT NULL,
    market_price DECIMAL(18, 2) NULL,
    status VARCHAR(20) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_sku_code UNIQUE (sku_code),
    CONSTRAINT fk_product_sku_spu FOREIGN KEY (spu_id) REFERENCES product_spu (id)
);

CREATE INDEX idx_product_sku_spu_status ON product_sku (spu_id, status);

CREATE TABLE product_media (
    id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    sku_id BIGINT NULL,
    object_key VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_media_object UNIQUE (object_key),
    CONSTRAINT fk_product_media_spu FOREIGN KEY (spu_id) REFERENCES product_spu (id),
    CONSTRAINT fk_product_media_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)
);

CREATE INDEX idx_product_media_spu_sort ON product_media (spu_id, sort_order, id);
