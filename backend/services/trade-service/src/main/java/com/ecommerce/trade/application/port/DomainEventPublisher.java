package com.ecommerce.trade.application.port;

public interface DomainEventPublisher {
    void publish(String topic, String tag, String payload) throws Exception;
}
