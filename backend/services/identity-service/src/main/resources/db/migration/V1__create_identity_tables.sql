CREATE TABLE user_account (
    id BIGINT NOT NULL,
    email VARCHAR(190) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_account_email UNIQUE (email)
);

CREATE TABLE identity_role (
    id BIGINT NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_identity_role_code UNIQUE (code)
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES identity_role (id)
);

CREATE TABLE refresh_token (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    last_used_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE INDEX idx_refresh_token_user ON refresh_token (user_id, expires_at);

CREATE TABLE login_record (
    id BIGINT NOT NULL,
    user_id BIGINT NULL,
    normalized_email VARCHAR(190) NOT NULL,
    successful BOOLEAN NOT NULL,
    failure_code VARCHAR(40) NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(300) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_login_record_email_time ON login_record (normalized_email, created_at);
CREATE INDEX idx_login_record_user_time ON login_record (user_id, created_at);
