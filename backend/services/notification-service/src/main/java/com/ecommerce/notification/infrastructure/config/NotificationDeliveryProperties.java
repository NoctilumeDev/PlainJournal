package com.ecommerce.notification.infrastructure.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.notification.email")
public record NotificationDeliveryProperties(
        boolean enabled,
        boolean workerEnabled,
        @Email @NotBlank String from,
        @NotBlank String workerId,
        @Min(1) @Max(20) int maximumAttempts,
        @NotNull Duration retryDelay,
        @NotNull Duration leaseDuration,
        @Min(1) @Max(100) int batchSize,
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay) {

    public NotificationDeliveryProperties {
        from = from == null || from.isBlank() ? "no-reply@plainjournal.local" : from;
        workerId = workerId == null || workerId.isBlank() ? "notification-local" : workerId;
        maximumAttempts = maximumAttempts <= 0 ? 5 : maximumAttempts;
        retryDelay = retryDelay == null ? Duration.ofSeconds(10) : retryDelay;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        fixedDelay = fixedDelay <= 0 ? 1000 : fixedDelay;
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("notification email retry delay must be positive");
        }
        if (leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "notification email lease duration must be positive and at most 10 minutes");
        }
    }
}
