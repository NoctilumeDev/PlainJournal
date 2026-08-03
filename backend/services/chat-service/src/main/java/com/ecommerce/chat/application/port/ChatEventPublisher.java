package com.ecommerce.chat.application.port;

import java.util.concurrent.CompletableFuture;

public interface ChatEventPublisher {

    CompletableFuture<Void> publish(
            String topic,
            String eventId,
            String eventType,
            String payload);
}
