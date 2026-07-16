package com.ecommerce.fulfillment.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.fulfillment.outbox")
public record OutboxProperties(
        boolean enabled,
        String endpoints,
        String topic,
        long fixedDelay,
        Duration retryDelay,
        int batchSize
) {
    public OutboxProperties {
        endpoints = endpoints == null ? "127.0.0.1:18082" : endpoints;
        topic = topic == null ? "ecommerce-logistics-events" : topic;
        fixedDelay = fixedDelay <= 0 ? 2000 : fixedDelay;
        retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        batchSize = batchSize <= 0 ? 50 : batchSize;
    }
}
