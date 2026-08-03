package com.ecommerce.marketing.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.marketing.flash-sale-outbox")
public record FlashSaleOutboxProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @Min(100) long fixedDelay,
        @NotNull Duration retryDelay,
        @Min(1) @Max(500) int batchSize,
        @NotBlank String publisherId,
        @NotNull Duration leaseDuration
) {
    public FlashSaleOutboxProperties {
        if (leaseDuration != null
                && (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0)) {
            throw new IllegalArgumentException("leaseDuration must be greater than zero and at most 10 minutes");
        }
    }
}
