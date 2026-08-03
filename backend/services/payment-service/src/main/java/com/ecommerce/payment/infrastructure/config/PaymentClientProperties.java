package com.ecommerce.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.payment.client")
public record PaymentClientProperties(
        Duration connectTimeout,
        Duration readTimeout,
        String tradeBaseUrl
) {
    public PaymentClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofMillis(500) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofMillis(1500) : readTimeout;
        tradeBaseUrl = tradeBaseUrl == null ? "http://trade-service" : tradeBaseUrl;
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("ecommerce.payment.client.connect-timeout must be positive");
        }
        if (readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("ecommerce.payment.client.read-timeout must be positive");
        }
        if (tradeBaseUrl.isBlank()) {
            throw new IllegalArgumentException("ecommerce.payment.client.trade-base-url must not be blank");
        }
    }
}
