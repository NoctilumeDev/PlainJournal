package com.ecommerce.marketing.infrastructure.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.marketing.flash-sale-result-consumer")
public record FlashSaleResultConsumerProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @NotBlank String consumerGroup,
        @Min(1) @Max(64) int batchSize,
        @NotNull Duration invisibleDuration,
        @NotNull Duration awaitDuration
) {
    public FlashSaleResultConsumerProperties {
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(30) : invisibleDuration;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
    }
}
