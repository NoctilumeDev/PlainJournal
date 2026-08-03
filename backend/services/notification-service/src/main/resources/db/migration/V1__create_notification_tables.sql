CREATE TABLE notification_recipient (
    user_id BIGINT NOT NULL,
    email VARCHAR(190) NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT ck_notification_recipient_email
        CHECK (email_enabled = FALSE OR email IS NOT NULL)
);

CREATE TABLE notification_task (
    id BIGINT NOT NULL,
    source_event_id VARCHAR(36) NOT NULL,
    source_event_type VARCHAR(80) NOT NULL,
    template_code VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_no VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_task_source_event UNIQUE (source_event_id)
);

CREATE INDEX idx_notification_task_user_created
    ON notification_task (user_id, created_at, id);

CREATE TABLE in_app_notification (
    id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    read_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_in_app_notification_task UNIQUE (task_id),
    CONSTRAINT fk_in_app_notification_task
        FOREIGN KEY (task_id) REFERENCES notification_task (id),
    CONSTRAINT ck_in_app_notification_status
        CHECK (status IN ('UNREAD', 'READ'))
);

CREATE INDEX idx_in_app_notification_user_page
    ON in_app_notification (user_id, created_at, id);
CREATE INDEX idx_in_app_notification_user_unread
    ON in_app_notification (user_id, status, created_at);

CREATE TABLE notification_delivery (
    id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    channel VARCHAR(24) NOT NULL,
    destination VARCHAR(320) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claim_owner VARCHAR(128) NULL,
    claim_until TIMESTAMP(3) NULL,
    provider_message_id VARCHAR(190) NOT NULL,
    sent_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_delivery_task_channel UNIQUE (task_id, channel),
    CONSTRAINT fk_notification_delivery_task
        FOREIGN KEY (task_id) REFERENCES notification_task (id),
    CONSTRAINT ck_notification_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'RETRY', 'SENT', 'NEEDS_ATTENTION'))
);

CREATE INDEX idx_notification_delivery_due
    ON notification_delivery (channel, status, next_attempt_at, claim_until);

CREATE TABLE notification_delivery_retry_audit (
    id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    delivery_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_status VARCHAR(24) NOT NULL,
    after_status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_delivery_retry_command UNIQUE (command_id),
    CONSTRAINT fk_notification_delivery_retry
        FOREIGN KEY (delivery_id) REFERENCES notification_delivery (id)
);

CREATE INDEX idx_notification_delivery_retry_delivery
    ON notification_delivery_retry_audit (delivery_id, created_at);

CREATE TABLE consumed_event (
    event_id VARCHAR(36) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);

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
    CONSTRAINT ck_notification_consumer_failure_attempts CHECK (attempts > 0)
);

CREATE INDEX idx_notification_consumer_failure_status
    ON consumer_failure (status, last_failed_at);
