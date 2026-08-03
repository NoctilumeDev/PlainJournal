package com.ecommerce.trade.infrastructure.messaging;

public enum ProcessTerminationPoint {
    OUTBOX_BEFORE_PUBLISH,
    OUTBOX_AFTER_BROKER_ACK,
    CONSUMER_AFTER_COMMIT
}
