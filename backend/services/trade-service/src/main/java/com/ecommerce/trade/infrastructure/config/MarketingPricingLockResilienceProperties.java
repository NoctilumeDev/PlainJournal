package com.ecommerce.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("ecommerce.trade.client.marketing-pricing-lock")
public record MarketingPricingLockResilienceProperties(
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("1500ms") Duration readTimeout,
        @DefaultValue("5s") Duration totalBudget,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("100ms") Duration retryWait,
        @DefaultValue("8") int maxConcurrentCalls,
        @DefaultValue("0ms") Duration bulkheadMaxWait,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration openStateWait,
        @DefaultValue("2") int halfOpenCalls
) {

    public MarketingPricingLockResilienceProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requirePositive(totalBudget, "total-budget");
        requireNonNegative(retryWait, "retry-wait");
        requireNonNegative(bulkheadMaxWait, "bulkhead-max-wait");
        requirePositive(openStateWait, "open-state-wait");
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw invalid("max-attempts must be between 1 and 3");
        }
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
            throw invalid("max-concurrent-calls must be between 1 and 64");
        }
        if (slidingWindowSize < 2 || slidingWindowSize > 100) {
            throw invalid("sliding-window-size must be between 2 and 100");
        }
        if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
            throw invalid("minimum-number-of-calls must be between 1 and sliding-window-size");
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw invalid("failure-rate-threshold must be greater than 0 and at most 100");
        }
        if (halfOpenCalls < 1 || halfOpenCalls > 10) {
            throw invalid("half-open-calls must be between 1 and 10");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(property + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String property) {
        if (value == null || value.isNegative()) {
            throw invalid(property + " must not be negative");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
                "ecommerce.trade.client.marketing-pricing-lock." + message);
    }
}
