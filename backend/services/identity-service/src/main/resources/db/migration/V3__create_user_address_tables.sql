CREATE TABLE user_address (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recipient_name VARCHAR(60) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    province VARCHAR(60) NOT NULL,
    city VARCHAR(60) NOT NULL,
    district VARCHAR(60) NOT NULL,
    detail_address VARCHAR(240) NOT NULL,
    postal_code VARCHAR(20) NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT ck_user_address_version CHECK (version >= 0)
);

CREATE INDEX idx_user_address_user_updated ON user_address (user_id, updated_at);
CREATE INDEX idx_user_address_user_default ON user_address (user_id, is_default);
