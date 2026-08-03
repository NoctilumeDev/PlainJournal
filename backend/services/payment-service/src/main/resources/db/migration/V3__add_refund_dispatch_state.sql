ALTER TABLE refund_order ADD COLUMN request_status VARCHAR(24) NOT NULL DEFAULT 'PENDING';
ALTER TABLE refund_order ADD COLUMN request_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE refund_order ADD COLUMN next_request_at TIMESTAMP(3) NULL;
ALTER TABLE refund_order ADD COLUMN request_claimed_at TIMESTAMP(3) NULL;
ALTER TABLE refund_order ADD COLUMN request_sent_at TIMESTAMP(3) NULL;
ALTER TABLE refund_order ADD COLUMN last_request_error VARCHAR(500) NULL;

UPDATE refund_order
SET next_request_at = updated_at
WHERE status = 'PROCESSING' AND next_request_at IS NULL;

UPDATE refund_order
SET request_status = 'SENT', request_sent_at = updated_at
WHERE status IN ('SUCCESS', 'FAILED');

CREATE INDEX idx_refund_dispatch ON refund_order (request_status, next_request_at);

ALTER TABLE refund_order ADD CONSTRAINT ck_refund_request_attempts CHECK (request_attempts >= 0);
