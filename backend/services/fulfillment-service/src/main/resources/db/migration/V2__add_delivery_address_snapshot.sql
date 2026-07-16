ALTER TABLE fulfillment_order ADD COLUMN source_address_id BIGINT NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN recipient_name VARCHAR(60) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN phone VARCHAR(30) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN province VARCHAR(60) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN city VARCHAR(60) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN district VARCHAR(60) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN detail_address VARCHAR(240) NOT NULL;
ALTER TABLE fulfillment_order ADD COLUMN postal_code VARCHAR(20) NULL;
