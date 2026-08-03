ALTER TABLE trade_order
    ADD COLUMN recovery_claim_owner VARCHAR(128) NULL;

ALTER TABLE trade_order
    ADD COLUMN recovery_claim_until TIMESTAMP(3) NULL;

CREATE INDEX idx_trade_order_recovery_claim
    ON trade_order (status, recovery_claim_until, next_recovery_at);

ALTER TABLE flash_sale_order_request
    ADD COLUMN recovery_claim_owner VARCHAR(128) NULL;

ALTER TABLE flash_sale_order_request
    ADD COLUMN recovery_claim_until TIMESTAMP(3) NULL;

CREATE INDEX idx_flash_sale_request_recovery_claim
    ON flash_sale_order_request (status, recovery_claim_until, next_attempt_at);
