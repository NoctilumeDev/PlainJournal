ALTER TABLE chat_attachment_upload
    ADD COLUMN quarantine_object_key VARCHAR(512) NULL;
ALTER TABLE chat_attachment_upload
    ADD COLUMN quarantine_cleanup_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE chat_attachment_upload
    ADD COLUMN quarantine_cleanup_last_error VARCHAR(500) NULL;
ALTER TABLE chat_attachment_upload
    ADD COLUMN quarantine_cleanup_claimed_at TIMESTAMP(3) NULL;

ALTER TABLE chat_attachment_upload
    ADD CONSTRAINT ck_chat_attachment_quarantine_cleanup_attempts
        CHECK (quarantine_cleanup_attempts >= 0);

CREATE INDEX idx_chat_attachment_quarantine_cleanup
    ON chat_attachment_upload (
        quarantine_object_key,
        quarantine_cleanup_claimed_at,
        updated_at
    );
