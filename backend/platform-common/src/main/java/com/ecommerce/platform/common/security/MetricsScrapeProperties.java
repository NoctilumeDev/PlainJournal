package com.ecommerce.platform.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.security.metrics")
public record MetricsScrapeProperties(String token) {

    public static final int MINIMUM_TOKEN_LENGTH = 32;

    public MetricsScrapeProperties {
        token = token == null ? "" : token.trim();
        if (!token.isEmpty() && token.length() < MINIMUM_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "ecommerce.security.metrics.token must contain at least 32 characters when configured");
        }
    }

    public boolean enabled() {
        return !token.isEmpty();
    }
}
