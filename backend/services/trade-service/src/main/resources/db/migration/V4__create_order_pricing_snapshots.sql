ALTER TABLE trade_order ADD COLUMN original_amount DECIMAL(18,2) NULL;
ALTER TABLE trade_order ADD COLUMN discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE trade_order ADD COLUMN marketing_lock_no VARCHAR(64) NULL;
UPDATE trade_order SET original_amount = total_amount WHERE original_amount IS NULL;
CREATE UNIQUE INDEX uk_trade_marketing_lock ON trade_order (marketing_lock_no);

ALTER TABLE order_item ADD COLUMN line_no INT NULL;
ALTER TABLE order_item ADD COLUMN discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE order_item ADD COLUMN payable_amount DECIMAL(18,2) NULL;
UPDATE order_item SET payable_amount = line_amount WHERE payable_amount IS NULL;
CREATE UNIQUE INDEX uk_order_item_line ON order_item (order_id, line_no);

CREATE TABLE order_benefit_selection (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    benefit_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_benefit_selection UNIQUE (order_id, benefit_no),
    CONSTRAINT fk_order_benefit_selection_order FOREIGN KEY (order_id) REFERENCES trade_order (id)
);

CREATE TABLE order_price_snapshot (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    marketing_lock_no VARCHAR(64) NOT NULL,
    original_amount DECIMAL(18,2) NOT NULL,
    coupon_discount DECIMAL(18,2) NOT NULL,
    red_packet_discount DECIMAL(18,2) NOT NULL,
    subsidy_discount DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    payable_amount DECIMAL(18,2) NOT NULL,
    pricing_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_price_snapshot_order UNIQUE (order_id),
    CONSTRAINT uk_order_price_snapshot_lock UNIQUE (marketing_lock_no),
    CONSTRAINT fk_order_price_snapshot_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT ck_order_price_snapshot_amounts CHECK (
        original_amount >= 0 AND coupon_discount >= 0 AND red_packet_discount >= 0
        AND subsidy_discount >= 0 AND discount_amount >= 0 AND payable_amount >= 0
        AND original_amount = discount_amount + payable_amount
        AND discount_amount = coupon_discount + red_packet_discount + subsidy_discount
    )
);

CREATE TABLE order_discount_allocation (
    id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    sku_id BIGINT NOT NULL,
    benefit_no VARCHAR(64) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    benefit_type VARCHAR(24) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_discount_allocation UNIQUE (order_id, benefit_no, line_no),
    CONSTRAINT fk_order_discount_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_order_discount_item FOREIGN KEY (order_item_id) REFERENCES order_item (id),
    CONSTRAINT ck_order_discount_amount CHECK (line_no > 0 AND discount_amount > 0)
);

CREATE INDEX idx_order_discount_item ON order_discount_allocation (order_item_id);
