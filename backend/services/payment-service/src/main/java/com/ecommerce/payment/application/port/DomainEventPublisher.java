package com.ecommerce.payment.application.port;

public interface DomainEventPublisher {
    void publish(String eventId, String eventType, String payload) throws Exception;
}
