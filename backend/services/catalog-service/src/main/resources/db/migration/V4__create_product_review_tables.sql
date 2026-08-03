CREATE TABLE review_eligibility (
    id BIGINT NOT NULL,
    source_event_id VARCHAR(36) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    line_no INT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_title VARCHAR(160) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(160) NOT NULL,
    spec_json VARCHAR(2000) NOT NULL,
    image_object_key VARCHAR(500) NULL,
    quantity BIGINT NOT NULL,
    completed_at TIMESTAMP(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_eligibility_order_line UNIQUE (order_no, line_no),
    CONSTRAINT uk_review_eligibility_source_line UNIQUE (source_event_id, line_no),
    CONSTRAINT ck_review_eligibility_line CHECK (line_no > 0),
    CONSTRAINT ck_review_eligibility_quantity CHECK (quantity > 0),
    CONSTRAINT ck_review_eligibility_status CHECK (status IN ('ELIGIBLE', 'REVIEWED'))
);

CREATE INDEX idx_review_eligibility_user_order
    ON review_eligibility (user_id, completed_at, order_no, line_no);
CREATE INDEX idx_review_eligibility_product
    ON review_eligibility (product_id, completed_at);

CREATE TABLE product_review (
    id BIGINT NOT NULL,
    eligibility_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    line_no INT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    like_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_review_eligibility UNIQUE (eligibility_id),
    CONSTRAINT uk_product_review_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_product_review_eligibility
        FOREIGN KEY (eligibility_id) REFERENCES review_eligibility (id),
    CONSTRAINT ck_product_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_review_status CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_product_review_like_count CHECK (like_count >= 0)
);

CREATE INDEX idx_product_review_public
    ON product_review (product_id, status, created_at, id);
CREATE INDEX idx_product_review_user
    ON product_review (user_id, created_at, id);

CREATE TABLE product_review_summary (
    product_id BIGINT NOT NULL,
    review_count BIGINT NOT NULL DEFAULT 0,
    rating_sum BIGINT NOT NULL DEFAULT 0,
    rating_1_count BIGINT NOT NULL DEFAULT 0,
    rating_2_count BIGINT NOT NULL DEFAULT 0,
    rating_3_count BIGINT NOT NULL DEFAULT 0,
    rating_4_count BIGINT NOT NULL DEFAULT 0,
    rating_5_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT ck_product_review_summary_count CHECK (review_count >= 0),
    CONSTRAINT ck_product_review_summary_sum CHECK (rating_sum >= 0),
    CONSTRAINT ck_product_review_summary_stars CHECK (
        rating_1_count >= 0 AND rating_2_count >= 0 AND rating_3_count >= 0
        AND rating_4_count >= 0 AND rating_5_count >= 0)
);

CREATE TABLE review_reply (
    id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_reply_review UNIQUE (review_id),
    CONSTRAINT uk_review_reply_command UNIQUE (command_id),
    CONSTRAINT fk_review_reply_review
        FOREIGN KEY (review_id) REFERENCES product_review (id)
);

CREATE TABLE review_like (
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (review_id, user_id),
    CONSTRAINT fk_review_like_review
        FOREIGN KEY (review_id) REFERENCES product_review (id)
);

CREATE INDEX idx_review_like_user ON review_like (user_id, created_at);

CREATE TABLE review_report (
    id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    detail VARCHAR(500) NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    resolution VARCHAR(20) NULL,
    resolved_by BIGINT NULL,
    resolved_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_report_reporter UNIQUE (review_id, reporter_user_id),
    CONSTRAINT fk_review_report_review
        FOREIGN KEY (review_id) REFERENCES product_review (id),
    CONSTRAINT ck_review_report_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_review_report_resolution CHECK (
        resolution IS NULL OR resolution IN ('UPHELD', 'REJECTED'))
);

CREATE INDEX idx_review_report_admin
    ON review_report (status, created_at, id);

CREATE TABLE review_moderation_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    report_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    resolution VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    review_status_before VARCHAR(20) NOT NULL,
    review_status_after VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_moderation_command UNIQUE (command_id),
    CONSTRAINT fk_review_moderation_report
        FOREIGN KEY (report_id) REFERENCES review_report (id),
    CONSTRAINT fk_review_moderation_review
        FOREIGN KEY (review_id) REFERENCES product_review (id),
    CONSTRAINT ck_review_moderation_resolution
        CHECK (resolution IN ('UPHELD', 'REJECTED'))
);

CREATE INDEX idx_review_moderation_review
    ON review_moderation_audit (review_id, created_at);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

CREATE TABLE consumer_failure (
    message_id VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    raw_payload TEXT NOT NULL,
    attempts INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_error VARCHAR(1000) NOT NULL,
    first_failed_at TIMESTAMP(3) NOT NULL,
    last_failed_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    PRIMARY KEY (message_id, consumer_group),
    CONSTRAINT ck_catalog_consumer_failure_attempts CHECK (attempts > 0)
);

CREATE INDEX idx_catalog_consumer_failure_status
    ON consumer_failure (status, last_failed_at);
