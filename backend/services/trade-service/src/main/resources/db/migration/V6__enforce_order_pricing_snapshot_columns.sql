UPDATE trade_order
SET original_amount = total_amount
WHERE original_amount IS NULL;

UPDATE order_item oi
SET line_no = (
    SELECT ranked.line_no
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY id) AS line_no
        FROM order_item
    ) ranked
    WHERE ranked.id = oi.id
)
WHERE oi.line_no IS NULL;

UPDATE order_item
SET payable_amount = line_amount - discount_amount
WHERE payable_amount IS NULL;

ALTER TABLE trade_order MODIFY COLUMN original_amount DECIMAL(18,2) NOT NULL;
ALTER TABLE order_item MODIFY COLUMN line_no INT NOT NULL;
ALTER TABLE order_item MODIFY COLUMN payable_amount DECIMAL(18,2) NOT NULL;

ALTER TABLE order_item ADD CONSTRAINT ck_order_item_line_no CHECK (line_no > 0);
