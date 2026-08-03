ALTER TABLE consumed_event
    ADD COLUMN owner_user_id BIGINT NULL AFTER consumer_group;

CREATE INDEX idx_trade_consumed_event_owner
    ON consumed_event (owner_user_id, consumed_at);
