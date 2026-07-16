package com.ecommerce.gateway.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.gateway.rate-limit")
public record GatewayRateLimitProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,24}") String namespace,
        boolean enabled,
        boolean redisEnabled,
        @NotNull Duration redisTimeout,
        @Min(100) long localMaximumSize,
        @Valid @NotNull Policy login,
        @Valid @NotNull Policy registration,
        @Valid @NotNull Policy refresh
) {

    public record Policy(@Min(1) int limit, @NotNull Duration window) {
    }
}
