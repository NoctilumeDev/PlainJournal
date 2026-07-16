package com.ecommerce.inventory.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.inventory.return-consumer")
public record ReturnEventConsumerProperties(
        boolean enabled,
        String endpoints,
        String topic,
        String consumerGroup,
        long fixedDelay,
        int batchSize,
        Duration invisibleDuration,
        Duration awaitDuration
) {
    public ReturnEventConsumerProperties {
        endpoints = endpoints == null ? "127.0.0.1:18082" : endpoints;
        topic = topic == null ? "ecommerce-logistics-events" : topic;
        consumerGroup = consumerGroup == null ? "inventory-return-inspected-v1" : consumerGroup;
        fixedDelay = fixedDelay <= 0 ? 1000 : fixedDelay;
        batchSize = batchSize <= 0 ? 16 : batchSize;
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(30) : invisibleDuration;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
    }
}
