package com.ecommerce.chat.infrastructure.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.chat.attachments.scan")
public record ChatAttachmentScanProperties(
        boolean enabled,
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(0) long initialDelay,
        @Min(250) long fixedDelay,
        @Min(1) @Max(50) int batchSize,
        @NotNull Duration leaseDuration,
        @Min(1) @Max(100) int maximumAttempts,
        @NotBlank String scannerId,
        @Min(256) @Max(65536) int maximumResponseBytes
) {
    public ChatAttachmentScanProperties {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(leaseDuration, "leaseDuration");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
