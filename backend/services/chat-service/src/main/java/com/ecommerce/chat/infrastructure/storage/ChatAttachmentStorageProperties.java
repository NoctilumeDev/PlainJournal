package com.ecommerce.chat.infrastructure.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.chat.attachments")
public record ChatAttachmentStorageProperties(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String namespace,
        @NotBlank String endpoint,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String bucket,
        @NotNull Duration uploadExpiry,
        @NotNull Duration downloadExpiry,
        @NotNull Duration intentTtl,
        @NotNull DataSize maximumSize,
        @Min(16) @Max(4096) int inspectionBytes,
        boolean cleanupEnabled,
        @Min(0) long cleanupInitialDelay,
        @Min(1000) long cleanupFixedDelay,
        @Min(1) @Max(200) int cleanupBatchSize,
        @NotNull Duration cleanupRecoveryAge
) {
    public ChatAttachmentStorageProperties {
        if (uploadExpiry != null && (uploadExpiry.isNegative() || uploadExpiry.isZero())) {
            throw new IllegalArgumentException("uploadExpiry must be greater than zero");
        }
        if (downloadExpiry != null && (downloadExpiry.isNegative() || downloadExpiry.isZero())) {
            throw new IllegalArgumentException("downloadExpiry must be greater than zero");
        }
        if (intentTtl != null && (intentTtl.isNegative() || intentTtl.isZero())) {
            throw new IllegalArgumentException("intentTtl must be greater than zero");
        }
        if (cleanupRecoveryAge != null
                && (cleanupRecoveryAge.isNegative() || cleanupRecoveryAge.isZero())) {
            throw new IllegalArgumentException("cleanupRecoveryAge must be greater than zero");
        }
    }
}
