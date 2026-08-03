CREATE TABLE chat_conversation (
    id BIGINT NOT NULL,
    conversation_no VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    assigned_agent_id BIGINT NULL,
    client_conversation_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    context_type VARCHAR(32) NULL,
    context_id VARCHAR(80) NULL,
    status VARCHAR(24) NOT NULL,
    last_message_sequence BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_conversation_no UNIQUE (conversation_no),
    CONSTRAINT uk_chat_customer_client_conversation UNIQUE (customer_id, client_conversation_id),
    CONSTRAINT ck_chat_conversation_sequence CHECK (last_message_sequence >= 0)
);

CREATE INDEX idx_chat_conversation_support
    ON chat_conversation (status, assigned_agent_id, updated_at);
CREATE INDEX idx_chat_conversation_customer
    ON chat_conversation (customer_id, updated_at);

CREATE TABLE conversation_member (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(24) NOT NULL,
    last_read_message_id BIGINT NULL,
    last_read_message_sequence BIGINT NULL,
    last_read_at TIMESTAMP(3) NULL,
    joined_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_conversation_member UNIQUE (conversation_id, user_id),
    CONSTRAINT fk_chat_member_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT ck_chat_member_read_sequence
        CHECK (last_read_message_sequence IS NULL OR last_read_message_sequence >= 0)
);

CREATE INDEX idx_chat_member_user
    ON conversation_member (user_id, conversation_id);

CREATE TABLE chat_message (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_message_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    message_sequence BIGINT NOT NULL,
    message_type VARCHAR(24) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_message_client
        UNIQUE (conversation_id, sender_id, client_message_id),
    CONSTRAINT uk_chat_message_sequence
        UNIQUE (conversation_id, message_sequence),
    CONSTRAINT fk_chat_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT ck_chat_message_sequence CHECK (message_sequence > 0)
);

CREATE INDEX idx_chat_message_conversation_created
    ON chat_message (conversation_id, created_at);

CREATE TABLE message_receipt (
    message_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    delivered_at TIMESTAMP(3) NULL,
    read_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (message_id, recipient_id),
    CONSTRAINT fk_chat_receipt_message
        FOREIGN KEY (message_id) REFERENCES chat_message (id)
);

CREATE INDEX idx_chat_receipt_recipient_state
    ON message_receipt (recipient_id, state, updated_at);

CREATE TABLE chat_attachment (
    id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_attachment_object UNIQUE (object_key),
    CONSTRAINT fk_chat_attachment_message
        FOREIGN KEY (message_id) REFERENCES chat_message (id),
    CONSTRAINT ck_chat_attachment_size CHECK (size_bytes > 0)
);

CREATE TABLE outbox_event (
    id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    aggregate_version INT NOT NULL,
    destination_topic VARCHAR(160) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claimed_at TIMESTAMP(3) NULL,
    claim_owner VARCHAR(128) NULL,
    claim_until TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_chat_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_chat_outbox_publishable
    ON outbox_event (status, next_attempt_at, created_at);
