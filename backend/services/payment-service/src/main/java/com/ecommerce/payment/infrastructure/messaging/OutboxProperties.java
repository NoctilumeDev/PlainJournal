package com.ecommerce.payment.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties("ecommerce.payment.outbox")
public record OutboxProperties(
        boolean enabled,
        String endpoints,
        String topic,
        long fixedDelay,
        Duration retryDelay,
        int batchSize,
        String publisherId,
        Duration leaseDuration
) {
    public OutboxProperties {
        endpoints = endpoints == null ? "127.0.0.1:18082" : endpoints;
        topic = topic == null ? "ecommerce-payment-events" : topic;
        fixedDelay = fixedDelay <= 0 ? 2000 : fixedDelay;
        retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        publisherId = publisherId == null || publisherId.isBlank()
                ? UUID.randomUUID().toString()
                : publisherId;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "ecommerce.payment.outbox.lease-duration must be between 1ms and 10m");
        }
    }
}
