package com.ecommerce.trade.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.Instant;

@Validated
@ConfigurationProperties("ecommerce.trade.distributed-id")
public record DistributedIdProperties(
        boolean enabled,
        String workerId,
        @NotBlank String namespace,
        @NotNull Duration leaseDuration,
        @NotNull Duration renewalInterval,
        @NotNull Instant epoch,
        @NotBlank String instanceId
) {
    public DistributedIdProperties {
        workerId = workerId == null ? "" : workerId.trim();
        namespace = namespace == null || namespace.isBlank() ? "trade-service" : namespace.trim();
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        renewalInterval = renewalInterval == null ? Duration.ofSeconds(10) : renewalInterval;
        epoch = epoch == null ? Instant.parse("2026-01-01T00:00:00Z") : epoch;
        instanceId = instanceId == null || instanceId.isBlank() ? "local" : instanceId.trim();
        if (namespace.length() > 64) {
            throw new IllegalArgumentException("distributed ID namespace must be at most 64 characters");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("distributed ID lease duration must be between 1ms and 10m");
        }
        if (renewalInterval.isNegative() || renewalInterval.isZero()
                || renewalInterval.compareTo(leaseDuration.dividedBy(2)) > 0) {
            throw new IllegalArgumentException(
                    "distributed ID renewal interval must be at most half the lease");
        }
    }

    public int resolvedWorkerId() {
        if (!workerId.isBlank()) {
            try {
                int explicit = Integer.parseInt(workerId);
                validateWorkerId(explicit);
                return explicit;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("distributed ID workerId must be an integer", exception);
            }
        }
        if ("local".equals(instanceId)) {
            return 0;
        }
        return Math.floorMod(instanceId.hashCode(), 1 << 10);
    }

    private static void validateWorkerId(int value) {
        if (value < 0 || value > 1023) {
            throw new IllegalArgumentException("distributed ID workerId must be between 0 and 1023");
        }
    }
}
