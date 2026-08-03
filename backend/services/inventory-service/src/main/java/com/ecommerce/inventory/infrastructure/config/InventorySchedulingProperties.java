package com.ecommerce.inventory.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.inventory.scheduling")
public record InventorySchedulingProperties(
        @Min(1) @Max(4) int defaultPoolSize,
        @Min(1) @Max(2) int outboxPoolSize,
        @Min(1) @Max(2) int controlPoolSize,
        @NotNull Duration shutdownAwait
) {
    public InventorySchedulingProperties {
        defaultPoolSize = defaultPoolSize == 0 ? 2 : defaultPoolSize;
        outboxPoolSize = outboxPoolSize == 0 ? 1 : outboxPoolSize;
        controlPoolSize = controlPoolSize == 0 ? 1 : controlPoolSize;
        shutdownAwait = shutdownAwait == null ? Duration.ofSeconds(10) : shutdownAwait;
        if (shutdownAwait.isNegative()
                || shutdownAwait.isZero()
                || shutdownAwait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "ecommerce.inventory.scheduling.shutdown-await must be between 1ms and 30s");
        }
    }
}
