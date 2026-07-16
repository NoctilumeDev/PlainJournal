package com.ecommerce.trade.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.trade.order")
public record OrderProperties(
        @NotBlank String defaultWarehouseCode,
        Duration paymentTimeout,
        Duration reservationGrace,
        boolean recoveryEnabled,
        @Positive long recoveryDelay,
        @Positive int recoveryBatchSize
) {
    public OrderProperties {
        paymentTimeout = paymentTimeout == null ? Duration.ofMinutes(15) : paymentTimeout;
        reservationGrace = reservationGrace == null ? Duration.ofMinutes(5) : reservationGrace;
    }
}
