ALTER TABLE consumed_event
    ADD COLUMN payload_fingerprint CHAR(64) NULL AFTER consumer_group;
