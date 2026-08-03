package com.ecommerce.trade.application.port;

import java.util.concurrent.CompletableFuture;

public interface DomainEventPublisher {
    void publish(String eventId, String eventType, String payload) throws Exception;

    default CompletableFuture<Void> publishAsync(String eventId, String eventType, String payload) {
        try {
            publish(eventId, eventType, payload);
            return CompletableFuture.completedFuture(null);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    default CompletableFuture<Void> publishAsync(
            String destinationTopic,
            String eventId,
            String eventType,
            String payload) {
        return publishAsync(eventId, eventType, payload);
    }
}
