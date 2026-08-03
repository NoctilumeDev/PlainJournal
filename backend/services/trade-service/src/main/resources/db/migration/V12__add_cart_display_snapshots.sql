ALTER TABLE cart_item
    ADD COLUMN product_title VARCHAR(200) NULL;

ALTER TABLE cart_item
    ADD COLUMN sku_name VARCHAR(120) NULL;

ALTER TABLE cart_item
    ADD COLUMN spec_json VARCHAR(2000) NULL;

ALTER TABLE cart_item
    ADD COLUMN unit_price DECIMAL(18,2) NULL;

ALTER TABLE cart_item
    ADD CONSTRAINT ck_cart_unit_price CHECK (unit_price IS NULL OR unit_price >= 0);
