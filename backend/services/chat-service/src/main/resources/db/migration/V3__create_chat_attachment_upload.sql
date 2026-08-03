CREATE TABLE chat_attachment_upload (
    id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    uploader_id BIGINT NOT NULL,
    client_upload_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    requested_mime_type VARCHAR(120) NOT NULL,
    requested_size_bytes BIGINT NOT NULL,
    verified_mime_type VARCHAR(120) NULL,
    verified_size_bytes BIGINT NULL,
    status VARCHAR(24) NOT NULL,
    message_id BIGINT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_attachment_upload_client
        UNIQUE (conversation_id, uploader_id, client_upload_id),
    CONSTRAINT uk_chat_attachment_upload_object UNIQUE (object_key),
    CONSTRAINT fk_chat_attachment_upload_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_attachment_upload_message
        FOREIGN KEY (message_id) REFERENCES chat_message (id),
    CONSTRAINT ck_chat_attachment_upload_requested_size
        CHECK (requested_size_bytes > 0),
    CONSTRAINT ck_chat_attachment_upload_verified_size
        CHECK (verified_size_bytes IS NULL OR verified_size_bytes > 0)
);

CREATE INDEX idx_chat_attachment_upload_owner_status
    ON chat_attachment_upload (conversation_id, uploader_id, status, expires_at);

ALTER TABLE chat_attachment
    ADD COLUMN upload_id BIGINT NOT NULL;
ALTER TABLE chat_attachment
    ADD COLUMN original_filename VARCHAR(255) NOT NULL;
ALTER TABLE chat_attachment
    ADD COLUMN sort_order INT NOT NULL;

ALTER TABLE chat_attachment
    ADD CONSTRAINT uk_chat_attachment_upload UNIQUE (upload_id);
ALTER TABLE chat_attachment
    ADD CONSTRAINT uk_chat_attachment_message_order UNIQUE (message_id, sort_order);
ALTER TABLE chat_attachment
    ADD CONSTRAINT fk_chat_attachment_upload
        FOREIGN KEY (upload_id) REFERENCES chat_attachment_upload (id);
ALTER TABLE chat_attachment
    ADD CONSTRAINT ck_chat_attachment_sort_order CHECK (sort_order >= 0);
