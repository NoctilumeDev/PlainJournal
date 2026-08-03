CREATE INDEX idx_chat_outbox_claim_lease
    ON outbox_event (status, claim_until);

CREATE INDEX idx_chat_outbox_aggregate_order
    ON outbox_event (
        aggregate_type,
        aggregate_id,
        aggregate_version,
        created_at,
        id,
        status
    );

CREATE INDEX idx_chat_receipt_offline_replay
    ON message_receipt (recipient_id, state, message_id);
