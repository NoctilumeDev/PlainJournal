CREATE TABLE cart_user_lock (
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id)
);

CREATE TABLE cart_merge_request (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    merge_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_merge_user_key UNIQUE (user_id, merge_key)
);

CREATE INDEX idx_cart_merge_user_created ON cart_merge_request (user_id, created_at);
