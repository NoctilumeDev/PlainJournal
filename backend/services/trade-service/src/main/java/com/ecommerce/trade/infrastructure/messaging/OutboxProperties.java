package com.ecommerce.trade.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.trade.outbox")
public record OutboxProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @NotBlank String flashSaleTopic,
        @Min(100) long fixedDelay,
        @NotNull Duration retryDelay,
        @Min(1) @Max(500) int batchSize,
        @Min(1) @Max(32) int parallelism,
        @NotBlank String publisherId,
        @NotNull Duration leaseDuration,
        @NotNull Duration shutdownAwait
) {
    public OutboxProperties {
        if (leaseDuration != null
                && (leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0)) {
            throw new IllegalArgumentException("leaseDuration must be greater than zero and at most 10 minutes");
        }
        if (shutdownAwait != null
                && (shutdownAwait.isZero()
                || shutdownAwait.isNegative()
                || shutdownAwait.compareTo(Duration.ofSeconds(30)) > 0)) {
            throw new IllegalArgumentException("shutdownAwait must be greater than zero and at most 30 seconds");
        }
    }
}
