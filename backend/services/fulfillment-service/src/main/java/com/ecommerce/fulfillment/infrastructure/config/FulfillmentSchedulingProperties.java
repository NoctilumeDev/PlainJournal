package com.ecommerce.fulfillment.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.fulfillment.scheduling")
public record FulfillmentSchedulingProperties(
        @Min(1) @Max(4) int defaultPoolSize,
        @Min(1) @Max(2) int outboxPoolSize,
        @Min(1) @Max(2) int controlPoolSize,
        @NotNull Duration shutdownAwait
) {
    public FulfillmentSchedulingProperties {
        defaultPoolSize = defaultPoolSize == 0 ? 2 : defaultPoolSize;
        outboxPoolSize = outboxPoolSize == 0 ? 1 : outboxPoolSize;
        controlPoolSize = controlPoolSize == 0 ? 1 : controlPoolSize;
        shutdownAwait = schedulingShutdownAwait(shutdownAwait, "ecommerce.fulfillment.scheduling");
    }

    private static Duration schedulingShutdownAwait(Duration value, String prefix) {
        Duration resolved = value == null ? Duration.ofSeconds(10) : value;
        if (resolved.isNegative()
                || resolved.isZero()
                || resolved.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    prefix + ".shutdown-await must be between 1ms and 30s");
        }
        return resolved;
    }
}
