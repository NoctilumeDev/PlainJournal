package com.ecommerce.inventory.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.inventory.reservation")
public record ReservationProperties(
        @NotNull Duration defaultTtl,
        boolean expiryEnabled,
        @Min(100) long expiryScanDelay,
        @Min(1) @Max(1000) int expiryBatchSize
) {
}
