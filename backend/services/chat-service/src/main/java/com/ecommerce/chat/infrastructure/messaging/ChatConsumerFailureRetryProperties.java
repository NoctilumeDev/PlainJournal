package com.ecommerce.chat.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.chat.consumer-failure-retry")
public record ChatConsumerFailureRetryProperties(
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay,
        @NotNull Duration retryDelay,
        @Min(1) @Max(100) int batchSize,
        @NotBlank String workerId,
        @NotNull Duration leaseDuration
) {
    public ChatConsumerFailureRetryProperties {
        fixedDelay = fixedDelay <= 0 ? 1000 : fixedDelay;
        retryDelay = retryDelay == null ? Duration.ofSeconds(15) : retryDelay;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        workerId = workerId == null || workerId.isBlank()
                ? "chat-local"
                : workerId;
        leaseDuration = leaseDuration == null
                ? Duration.ofSeconds(30)
                : leaseDuration;
        requirePositive(retryDelay, "retryDelay");
        requirePositive(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be at most 10 minutes");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
