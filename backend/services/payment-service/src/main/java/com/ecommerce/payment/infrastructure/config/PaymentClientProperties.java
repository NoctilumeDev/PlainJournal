package com.ecommerce.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.payment.client")
public record PaymentClientProperties(Duration connectTimeout, Duration readTimeout) {
    public PaymentClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(4) : readTimeout;
    }
}
