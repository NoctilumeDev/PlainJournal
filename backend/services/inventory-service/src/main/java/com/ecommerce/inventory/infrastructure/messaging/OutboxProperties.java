package com.ecommerce.inventory.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.inventory.outbox")
public record OutboxProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @Min(100) long fixedDelay,
        @NotNull Duration retryDelay,
        @Min(1) @Max(500) int batchSize,
        @Min(1) @Max(32) int parallelism,
        @NotBlank String publisherId,
        @NotNull Duration leaseDuration,
        @NotNull Duration shutdownAwait
) {
    public OutboxProperties {
        parallelism = parallelism == 0 ? 8 : parallelism;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
        shutdownAwait = shutdownAwait == null ? Duration.ofSeconds(10) : shutdownAwait;
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "ecommerce.inventory.outbox.lease-duration must be between 1ms and 10m");
        }
        if (shutdownAwait.isNegative()
                || shutdownAwait.isZero()
                || shutdownAwait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "ecommerce.inventory.outbox.shutdown-await must be between 1ms and 30s");
        }
    }
}
