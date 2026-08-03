ALTER TABLE chat_attachment_upload
    ADD COLUMN verified_sha256 CHAR(64) NULL;

ALTER TABLE chat_attachment
    ADD COLUMN sha256 CHAR(64) NOT NULL;
