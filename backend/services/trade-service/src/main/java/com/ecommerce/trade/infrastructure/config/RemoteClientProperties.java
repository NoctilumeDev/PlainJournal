package com.ecommerce.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.trade.client")
public record RemoteClientProperties(
        Duration connectTimeout,
        Duration readTimeout,
        String catalogBaseUrl,
        String identityBaseUrl,
        String inventoryBaseUrl,
        String marketingBaseUrl
) {
    public RemoteClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofMillis(500) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofMillis(1500) : readTimeout;
        catalogBaseUrl = catalogBaseUrl == null ? "http://catalog-service" : catalogBaseUrl;
        identityBaseUrl = identityBaseUrl == null ? "http://identity-service" : identityBaseUrl;
        inventoryBaseUrl = inventoryBaseUrl == null ? "http://inventory-service" : inventoryBaseUrl;
        marketingBaseUrl = marketingBaseUrl == null ? "http://marketing-service" : marketingBaseUrl;
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requireBaseUrl(catalogBaseUrl, "catalog-base-url");
        requireBaseUrl(identityBaseUrl, "identity-base-url");
        requireBaseUrl(inventoryBaseUrl, "inventory-base-url");
        requireBaseUrl(marketingBaseUrl, "marketing-base-url");
    }

    private static void requirePositive(Duration value, String property) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("ecommerce.trade.client." + property + " must be positive");
        }
    }

    private static void requireBaseUrl(String value, String property) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ecommerce.trade.client." + property + " must not be blank");
        }
    }
}
