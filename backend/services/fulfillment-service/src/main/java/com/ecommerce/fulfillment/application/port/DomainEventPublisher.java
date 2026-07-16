package com.ecommerce.fulfillment.application.port;

public interface DomainEventPublisher {
    void publish(String eventId, String eventType, String payload) throws Exception;
}
