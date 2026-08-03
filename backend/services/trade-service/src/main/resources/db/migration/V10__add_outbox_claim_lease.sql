ALTER TABLE outbox_event
    ADD COLUMN claim_owner VARCHAR(128) NULL;

ALTER TABLE outbox_event
    ADD COLUMN claim_until TIMESTAMP(3) NULL;

CREATE INDEX idx_trade_outbox_claim_lease
    ON outbox_event (status, claim_until);

CREATE INDEX idx_trade_outbox_aggregate_order
    ON outbox_event (
        aggregate_type,
        aggregate_id,
        aggregate_version,
        created_at,
        id,
        status
    );
