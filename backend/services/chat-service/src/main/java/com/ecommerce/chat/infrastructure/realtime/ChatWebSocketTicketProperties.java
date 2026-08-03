package com.ecommerce.chat.infrastructure.realtime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.chat.websocket-ticket")
public record ChatWebSocketTicketProperties(
        boolean enabled,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]+")
        String namespace,
        @NotNull Duration ttl,
        @NotBlank
        @Pattern(regexp = "/[A-Za-z0-9/_-]+")
        String targetPath,
        @Min(24) @Max(64) int entropyBytes
) {
    private static final Duration MINIMUM_TTL = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(2);

    public ChatWebSocketTicketProperties {
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        ttl = ttl == null ? Duration.ofSeconds(30) : ttl;
        targetPath = targetPath == null || targetPath.isBlank() ? "/ws/chat" : targetPath;
        entropyBytes = entropyBytes <= 0 ? 32 : entropyBytes;
        if (ttl.compareTo(MINIMUM_TTL) < 0 || ttl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException(
                    "websocket ticket ttl must be between 5 seconds and 2 minutes");
        }
    }

    public String redisKeyPrefix() {
        return "ecommerce:" + namespace + ":chat:ws-ticket:";
    }
}
