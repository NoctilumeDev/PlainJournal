CREATE INDEX idx_product_spu_status_created_id
    ON product_spu (status, created_at, id);

CREATE INDEX idx_product_spu_status_category_created_id
    ON product_spu (status, category_id, created_at, id);
