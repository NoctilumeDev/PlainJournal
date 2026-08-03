ALTER TABLE refund_order
    ADD COLUMN request_claim_owner VARCHAR(128) NULL;

ALTER TABLE refund_order
    ADD COLUMN request_claim_until TIMESTAMP(3) NULL;

CREATE INDEX idx_refund_dispatch_claim_lease
    ON refund_order (request_status, request_claim_until);

UPDATE refund_order
SET request_status = 'PENDING',
    request_claimed_at = NULL,
    request_claim_owner = NULL,
    request_claim_until = NULL,
    next_request_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING' AND request_status = 'REQUESTING';
