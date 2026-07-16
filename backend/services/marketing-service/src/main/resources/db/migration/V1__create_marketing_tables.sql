CREATE TABLE marketing_rule (
    id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    benefit_type VARCHAR(24) NOT NULL,
    threshold_amount DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    stack_order INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    valid_from TIMESTAMP(3) NOT NULL,
    valid_until TIMESTAMP(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_rule_code UNIQUE (rule_code),
    CONSTRAINT ck_marketing_rule_amounts CHECK (threshold_amount >= 0 AND discount_amount > 0),
    CONSTRAINT ck_marketing_rule_window CHECK (valid_until > valid_from),
    CONSTRAINT ck_marketing_rule_version CHECK (version >= 0)
);

CREATE INDEX idx_marketing_rule_active ON marketing_rule (status, valid_from, valid_until);

CREATE TABLE marketing_rule_region (
    id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    region_level VARCHAR(20) NOT NULL,
    region_code CHAR(6) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_marketing_rule_region UNIQUE (rule_id, region_level, region_code),
    CONSTRAINT fk_marketing_rule_region_rule FOREIGN KEY (rule_id) REFERENCES marketing_rule (id)
);

CREATE TABLE user_benefit (
    id BIGINT NOT NULL,
    benefit_no VARCHAR(64) NOT NULL,
    grant_key VARCHAR(100) NOT NULL,
    rule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_order_no VARCHAR(64) NULL,
    locked_at TIMESTAMP(3) NULL,
    redeemed_order_no VARCHAR(64) NULL,
    redeemed_at TIMESTAMP(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_benefit_no UNIQUE (benefit_no),
    CONSTRAINT uk_user_benefit_grant UNIQUE (user_id, grant_key),
    CONSTRAINT fk_user_benefit_rule FOREIGN KEY (rule_id) REFERENCES marketing_rule (id),
    CONSTRAINT ck_user_benefit_version CHECK (version >= 0)
);

CREATE INDEX idx_user_benefit_user_status ON user_benefit (user_id, status, created_at);
CREATE INDEX idx_user_benefit_locked_order ON user_benefit (locked_order_no);

CREATE TABLE pricing_lock (
    id BIGINT NOT NULL,
    lock_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    original_amount DECIMAL(18,2) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    payable_amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    locked_at TIMESTAMP(3) NULL,
    released_at TIMESTAMP(3) NULL,
    redeemed_at TIMESTAMP(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pricing_lock_no UNIQUE (lock_no),
    CONSTRAINT uk_pricing_lock_order UNIQUE (order_no),
    CONSTRAINT ck_pricing_lock_amounts CHECK (
        original_amount >= 0 AND discount_amount >= 0 AND payable_amount >= 0
        AND original_amount = discount_amount + payable_amount
    ),
    CONSTRAINT ck_pricing_lock_version CHECK (version >= 0)
);

CREATE INDEX idx_pricing_lock_user_created ON pricing_lock (user_id, created_at);
CREATE INDEX idx_pricing_lock_status_updated ON pricing_lock (status, updated_at);

CREATE TABLE pricing_lock_benefit (
    id BIGINT NOT NULL,
    lock_id BIGINT NOT NULL,
    user_benefit_id BIGINT NOT NULL,
    benefit_no VARCHAR(64) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    benefit_type VARCHAR(24) NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pricing_lock_benefit UNIQUE (lock_id, benefit_no),
    CONSTRAINT fk_pricing_lock_benefit_lock FOREIGN KEY (lock_id) REFERENCES pricing_lock (id),
    CONSTRAINT fk_pricing_lock_benefit_user FOREIGN KEY (user_benefit_id) REFERENCES user_benefit (id),
    CONSTRAINT ck_pricing_lock_benefit_amount CHECK (discount_amount >= 0)
);

CREATE TABLE pricing_lock_allocation (
    id BIGINT NOT NULL,
    lock_id BIGINT NOT NULL,
    benefit_no VARCHAR(64) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    benefit_type VARCHAR(24) NOT NULL,
    line_no INT NOT NULL,
    sku_id BIGINT NOT NULL,
    discount_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pricing_lock_allocation UNIQUE (lock_id, benefit_no, line_no),
    CONSTRAINT fk_pricing_lock_allocation_lock FOREIGN KEY (lock_id) REFERENCES pricing_lock (id),
    CONSTRAINT ck_pricing_lock_allocation_amount CHECK (line_no > 0 AND discount_amount >= 0)
);

CREATE INDEX idx_pricing_allocation_lock_line ON pricing_lock_allocation (lock_id, line_no);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
