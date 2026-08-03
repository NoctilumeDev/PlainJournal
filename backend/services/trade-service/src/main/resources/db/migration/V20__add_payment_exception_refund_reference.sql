ALTER TABLE trade_order
    ADD COLUMN exception_refund_no VARCHAR(64) NULL AFTER payment_no;

CREATE UNIQUE INDEX uk_trade_order_exception_refund
    ON trade_order (exception_refund_no);
