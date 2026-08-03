package com.ecommerce.catalog.infrastructure.cache;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.catalog.cache")
public record CatalogCacheProperties(
        boolean enabled,
        @NotBlank String namespace,
        @Min(100) @Max(100_000) int localMaximumSize,
        @NotNull Duration localTtl,
        @NotNull Duration freshTtl,
        @NotNull Duration staleTtl,
        @NotNull Duration negativeTtl,
        @DecimalMin("0.0") @DecimalMax("0.5") double ttlJitter,
        @NotNull Duration rebuildWait,
        @Min(1) @Max(64) int rebuildMaxConcurrent,
        @Min(1) @Max(16) int refreshThreads,
        @Min(1) @Max(10_000) int refreshQueueCapacity,
        @NotNull Duration distributedLockTtl,
        @NotBlank String invalidationChannel
) {

    public CatalogCacheProperties {
        requirePositive(localTtl, "local-ttl");
        requirePositive(freshTtl, "fresh-ttl");
        requirePositive(staleTtl, "stale-ttl");
        requirePositive(negativeTtl, "negative-ttl");
        requireNonNegative(rebuildWait, "rebuild-wait");
        requirePositive(distributedLockTtl, "distributed-lock-ttl");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("ecommerce.catalog.cache." + name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    "ecommerce.catalog.cache." + name + " must not be negative");
        }
    }
}
