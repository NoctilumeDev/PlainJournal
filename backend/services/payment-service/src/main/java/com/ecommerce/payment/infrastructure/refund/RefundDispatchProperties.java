package com.ecommerce.payment.infrastructure.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties("ecommerce.payment.refund-dispatch")
public record RefundDispatchProperties(
        boolean enabled,
        long fixedDelay,
        long initialDelay,
        Duration retryDelay,
        String dispatcherId,
        Duration claimTimeout,
        int batchSize,
        int maxAttempts
) {
    public RefundDispatchProperties {
        fixedDelay = fixedDelay <= 0 ? 2000 : fixedDelay;
        initialDelay = initialDelay < 0 ? 0 : initialDelay;
        retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        dispatcherId = dispatcherId == null || dispatcherId.isBlank()
                ? UUID.randomUUID().toString()
                : dispatcherId;
        claimTimeout = claimTimeout == null ? Duration.ofMinutes(5) : claimTimeout;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 10 : maxAttempts;
        if (claimTimeout.isNegative()
                || claimTimeout.isZero()
                || claimTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "ecommerce.payment.refund-dispatch.claim-timeout must be between 1ms and 10m");
        }
    }
}
