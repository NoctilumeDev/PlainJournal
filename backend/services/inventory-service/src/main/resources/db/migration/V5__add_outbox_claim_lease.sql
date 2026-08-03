ALTER TABLE outbox_event
    ADD COLUMN claimed_at TIMESTAMP(3) NULL;

ALTER TABLE outbox_event
    ADD COLUMN claim_owner VARCHAR(128) NULL;

ALTER TABLE outbox_event
    ADD COLUMN claim_until TIMESTAMP(3) NULL;

CREATE INDEX idx_inventory_outbox_claim_lease
    ON outbox_event (status, claim_until);

UPDATE outbox_event
SET status = 'PENDING',
    claimed_at = NULL,
    claim_owner = NULL,
    claim_until = NULL,
    next_attempt_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING';
