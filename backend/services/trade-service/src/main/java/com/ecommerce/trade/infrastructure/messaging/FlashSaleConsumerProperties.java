package com.ecommerce.trade.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.trade.flash-sale-consumer")
public record FlashSaleConsumerProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @NotBlank String consumerGroup,
        @Min(1) @Max(64) int batchSize,
        @NotNull Duration invisibleDuration,
        @NotNull Duration awaitDuration,
        @Min(1) @Max(100) int maxAttempts,
        @Min(100) long recoveryDelay,
        @Min(1) @Max(500) int recoveryBatchSize,
        @NotNull Duration recoveryLease
) {
    public FlashSaleConsumerProperties {
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(30) : invisibleDuration;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
        maxAttempts = maxAttempts <= 0 ? 16 : maxAttempts;
        recoveryDelay = recoveryDelay <= 0 ? 1000 : recoveryDelay;
        recoveryBatchSize = recoveryBatchSize <= 0 ? 100 : recoveryBatchSize;
        recoveryLease = recoveryLease == null ? Duration.ofSeconds(30) : recoveryLease;
    }
}
