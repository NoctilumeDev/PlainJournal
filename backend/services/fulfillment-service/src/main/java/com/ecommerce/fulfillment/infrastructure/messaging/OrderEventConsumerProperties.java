package com.ecommerce.fulfillment.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.fulfillment.order-consumer")
public record OrderEventConsumerProperties(
        boolean enabled,
        String endpoints,
        String topic,
        String consumerGroup,
        long fixedDelay,
        int batchSize,
        Duration invisibleDuration,
        Duration awaitDuration
) {
    public OrderEventConsumerProperties {
        endpoints = endpoints == null ? "127.0.0.1:18082" : endpoints;
        topic = topic == null ? "ecommerce-order-events" : topic;
        consumerGroup = consumerGroup == null ? "fulfillment-order-paid-v1" : consumerGroup;
        fixedDelay = fixedDelay <= 0 ? 1000 : fixedDelay;
        batchSize = batchSize <= 0 ? 16 : batchSize;
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(30) : invisibleDuration;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
    }
}
