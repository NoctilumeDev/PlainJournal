ALTER TABLE flash_sale_admission
    MODIFY COLUMN remaining_admissions INT NULL;

ALTER TABLE flash_sale_admission
    MODIFY COLUMN accepted_at TIMESTAMP(3) NULL;
