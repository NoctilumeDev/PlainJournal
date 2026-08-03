package com.ecommerce.analytics.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.analytics.events")
public record AnalyticsEventConsumerProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String consumerGroup,
        @NotBlank String tradeTopic,
        @NotBlank String paymentTopic,
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay,
        @NotNull Duration awaitDuration,
        @NotNull Duration invisibleDuration,
        @Min(1) @Max(100) int batchSize) {

    public AnalyticsEventConsumerProperties {
        endpoints = defaultText(endpoints, "127.0.0.1:18082");
        consumerGroup = defaultText(consumerGroup, "ecommerce-analytics-domain-events");
        tradeTopic = defaultText(tradeTopic, "ecommerce-order-events");
        paymentTopic = defaultText(paymentTopic, "ecommerce-payment-events");
        fixedDelay = fixedDelay <= 0 ? 500 : fixedDelay;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(20) : invisibleDuration;
        batchSize = batchSize <= 0 ? 20 : batchSize;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
