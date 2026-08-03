package com.ecommerce.marketing.infrastructure.flashsale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.marketing.flash-sale")
public record FlashSaleAdmissionProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,24}") String namespace,
        boolean redisEnabled,
        @NotNull Duration resultRetention,
        @NotNull Duration processingTimeout,
        @Min(100) long timeoutScanDelay,
        @Min(1) @Max(500) int timeoutScanBatchSize,
        @Min(100) long pendingRecoveryDelay,
        @Min(1) @Max(500) int pendingRecoveryBatchSize
) {
    public FlashSaleAdmissionProperties {
        processingTimeout = processingTimeout == null ? Duration.ofMinutes(2) : processingTimeout;
        timeoutScanDelay = timeoutScanDelay <= 0 ? 5000 : timeoutScanDelay;
        timeoutScanBatchSize = timeoutScanBatchSize <= 0 ? 100 : timeoutScanBatchSize;
        pendingRecoveryDelay = pendingRecoveryDelay <= 0 ? 1000 : pendingRecoveryDelay;
        pendingRecoveryBatchSize = pendingRecoveryBatchSize <= 0 ? 100 : pendingRecoveryBatchSize;
        if (processingTimeout.isNegative()
                || processingTimeout.isZero()
                || processingTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("processingTimeout must be greater than zero and at most 1 hour");
        }
    }
}
