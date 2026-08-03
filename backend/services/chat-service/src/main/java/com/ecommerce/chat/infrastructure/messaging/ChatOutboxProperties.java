package com.ecommerce.chat.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.chat.outbox")
public record ChatOutboxProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay,
        @NotNull Duration retryDelay,
        @Min(1) @Max(500) int batchSize,
        @NotBlank String publisherId,
        @NotNull Duration leaseDuration
) {
    public ChatOutboxProperties {
        endpoints = endpoints == null || endpoints.isBlank()
                ? "127.0.0.1:18082"
                : endpoints;
        topic = topic == null || topic.isBlank()
                ? "ecommerce-chat-events"
                : topic;
        fixedDelay = fixedDelay <= 0 ? 1000 : fixedDelay;
        retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        batchSize = batchSize <= 0 ? 50 : batchSize;
        publisherId = publisherId == null || publisherId.isBlank()
                ? "chat-local"
                : publisherId;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        if (retryDelay != null && (retryDelay.isNegative() || retryDelay.isZero())) {
            throw new IllegalArgumentException("retryDelay must be greater than zero");
        }
        if (leaseDuration != null
                && (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0)) {
            throw new IllegalArgumentException("leaseDuration must be greater than zero and at most 10 minutes");
        }
    }
}
