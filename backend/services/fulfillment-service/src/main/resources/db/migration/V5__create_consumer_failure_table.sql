CREATE TABLE consumer_failure (
    message_id VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    raw_payload TEXT NOT NULL,
    attempts INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_error VARCHAR(1000) NOT NULL,
    first_failed_at TIMESTAMP(3) NOT NULL,
    last_failed_at TIMESTAMP(3) NOT NULL,
    recovered_at TIMESTAMP(3) NULL,
    PRIMARY KEY (message_id, consumer_group),
    CONSTRAINT ck_fulfillment_consumer_failure_attempts CHECK (attempts > 0)
);

CREATE INDEX idx_fulfillment_consumer_failure_status ON consumer_failure (status, last_failed_at);
