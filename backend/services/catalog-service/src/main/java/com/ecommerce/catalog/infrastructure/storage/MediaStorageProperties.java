package com.ecommerce.catalog.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.catalog.media")
public record MediaStorageProperties(
        @NotBlank String endpoint,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String bucket,
        @NotNull Duration uploadExpiry,
        @NotNull Duration downloadExpiry,
        @NotNull DataSize maximumSize
) {
}
