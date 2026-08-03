package com.ecommerce.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("ecommerce.trade.client.synchronous-boundary")
public record SynchronousBoundaryResilienceProperties(
        @DefaultValue("5s") Duration totalBudget,
        @DefaultValue("2") int queryMaxAttempts,
        @DefaultValue("100ms") Duration retryWait,
        @DefaultValue("16") int queryMaxConcurrentCalls,
        @DefaultValue("8") int commandMaxConcurrentCalls,
        @DefaultValue("0ms") Duration bulkheadMaxWait,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration openStateWait,
        @DefaultValue("2") int halfOpenCalls
) {

    public SynchronousBoundaryResilienceProperties {
        requirePositive(totalBudget, "total-budget");
        requireNonNegative(retryWait, "retry-wait");
        requireNonNegative(bulkheadMaxWait, "bulkhead-max-wait");
        requirePositive(openStateWait, "open-state-wait");
        if (queryMaxAttempts < 1 || queryMaxAttempts > 3) {
            throw invalid("query-max-attempts must be between 1 and 3");
        }
        requireConcurrency(queryMaxConcurrentCalls, "query-max-concurrent-calls");
        requireConcurrency(commandMaxConcurrentCalls, "command-max-concurrent-calls");
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

    private static void requireConcurrency(int value, String property) {
        if (value < 1 || value > 128) {
            throw invalid(property + " must be between 1 and 128");
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
                "ecommerce.trade.client.synchronous-boundary." + message);
    }
}
