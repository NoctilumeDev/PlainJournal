package com.ecommerce.marketing.infrastructure.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.marketing.order-consumer")
public record OrderEventConsumerProperties(
        boolean enabled,
        @NotBlank String endpoints,
        @NotBlank String topic,
        @NotBlank String consumerGroup,
        @Positive int batchSize,
        Duration invisibleDuration,
        Duration awaitDuration
) {
    public OrderEventConsumerProperties {
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(30) : invisibleDuration;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
    }
}
