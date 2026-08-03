package com.ecommerce.catalog.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.catalog.review-events")
public record ReviewEventConsumerProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String consumerGroup,
        @NotBlank String topic,
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay,
        @NotNull Duration awaitDuration,
        @NotNull Duration invisibleDuration,
        @Min(1) @Max(100) int batchSize) {

    public ReviewEventConsumerProperties {
        endpoints = defaultText(endpoints, "127.0.0.1:18082");
        consumerGroup = defaultText(
                consumerGroup,
                "ecommerce-catalog-order-completed-reviews");
        topic = defaultText(topic, "ecommerce-order-events");
        fixedDelay = fixedDelay <= 0 ? 500 : fixedDelay;
        awaitDuration = awaitDuration == null
                ? Duration.ofSeconds(5)
                : awaitDuration;
        invisibleDuration = invisibleDuration == null
                ? Duration.ofSeconds(20)
                : invisibleDuration;
        batchSize = batchSize <= 0 ? 20 : batchSize;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
