ALTER TABLE consumer_failure
    ADD COLUMN next_attempt_at TIMESTAMP(3) NULL;

ALTER TABLE consumer_failure
    ADD COLUMN claimed_at TIMESTAMP(3) NULL;

ALTER TABLE consumer_failure
    ADD COLUMN claim_owner VARCHAR(128) NULL;

ALTER TABLE consumer_failure
    ADD COLUMN claim_until TIMESTAMP(3) NULL;

UPDATE consumer_failure
SET next_attempt_at = last_failed_at
WHERE status = 'RETRYING'
  AND next_attempt_at IS NULL;

CREATE INDEX idx_fulfillment_consumer_failure_retry
    ON consumer_failure (status, next_attempt_at, claim_until);
