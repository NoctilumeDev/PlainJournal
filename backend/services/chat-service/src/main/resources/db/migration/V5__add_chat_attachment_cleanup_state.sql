ALTER TABLE chat_attachment_upload
    ADD COLUMN cleanup_claimed_at TIMESTAMP(3) NULL;
ALTER TABLE chat_attachment_upload
    ADD COLUMN cleanup_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE chat_attachment_upload
    ADD COLUMN cleanup_last_error VARCHAR(500) NULL;
ALTER TABLE chat_attachment_upload
    ADD COLUMN cleaned_at TIMESTAMP(3) NULL;

ALTER TABLE chat_attachment_upload
    ADD CONSTRAINT ck_chat_attachment_cleanup_attempts CHECK (cleanup_attempts >= 0);

CREATE INDEX idx_chat_attachment_upload_cleanup
    ON chat_attachment_upload (status, expires_at, cleanup_claimed_at);
