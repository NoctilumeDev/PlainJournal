package com.ecommerce.trade.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.trade.scheduling")
public record TradeSchedulingProperties(
        @Min(1) @Max(4) int defaultPoolSize,
        @Min(1) @Max(2) int orderRecoveryPoolSize,
        @Min(1) @Max(2) int outboxPoolSize,
        @Min(1) @Max(4) int flashSalePoolSize,
        @Min(1) @Max(2) int consumerFailurePoolSize,
        @NotNull Duration shutdownAwait
) {
    public TradeSchedulingProperties {
        defaultPoolSize = defaultPoolSize == 0 ? 1 : defaultPoolSize;
        orderRecoveryPoolSize = orderRecoveryPoolSize == 0 ? 1 : orderRecoveryPoolSize;
        outboxPoolSize = outboxPoolSize == 0 ? 1 : outboxPoolSize;
        flashSalePoolSize = flashSalePoolSize == 0 ? 2 : flashSalePoolSize;
        consumerFailurePoolSize = consumerFailurePoolSize == 0 ? 1 : consumerFailurePoolSize;
        shutdownAwait = shutdownAwait == null ? Duration.ofSeconds(10) : shutdownAwait;
        if (shutdownAwait.isNegative() || shutdownAwait.isZero() || shutdownAwait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("ecommerce.trade.scheduling.shutdown-await must be between 1ms and 30s");
        }
    }
}
