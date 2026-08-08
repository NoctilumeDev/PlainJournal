package com.ecommerce.identity.infrastructure.security;

import com.ecommerce.identity.application.port.LoginLockPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.security.login-attempt")
public record LoginAttemptProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,24}") String namespace,
        boolean redisEnabled,
        @Min(2) int maxFailures,
        @NotNull Duration failureWindow,
        @NotNull Duration lockDuration,
        @Min(100) long localMaximumSize
) implements LoginLockPolicy {
}
