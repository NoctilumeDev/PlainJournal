ALTER TABLE trade_order ADD COLUMN payment_no VARCHAR(64) NULL;
ALTER TABLE trade_order ADD COLUMN fulfillment_no VARCHAR(64) NULL;

UPDATE trade_order trade
SET payment_no = (
    SELECT MAX(history.reason)
    FROM order_status_history history
    WHERE history.order_id = trade.id
      AND history.command IN ('PAYMENT_SUCCEEDED', 'LATE_PAYMENT_DETECTED')
)
WHERE payment_no IS NULL
  AND EXISTS (
    SELECT 1
    FROM order_status_history history
    WHERE history.order_id = trade.id
      AND history.command IN ('PAYMENT_SUCCEEDED', 'LATE_PAYMENT_DETECTED')
);

UPDATE trade_order trade
SET fulfillment_no = (
    SELECT MAX(history.reason)
    FROM order_status_history history
    WHERE history.order_id = trade.id
      AND history.command = 'FULFILLMENT_CREATED'
)
WHERE fulfillment_no IS NULL
  AND EXISTS (
    SELECT 1
    FROM order_status_history history
    WHERE history.order_id = trade.id
      AND history.command = 'FULFILLMENT_CREATED'
);

CREATE UNIQUE INDEX uk_trade_order_payment_no ON trade_order (payment_no);
CREATE UNIQUE INDEX uk_trade_order_fulfillment_no ON trade_order (fulfillment_no);
