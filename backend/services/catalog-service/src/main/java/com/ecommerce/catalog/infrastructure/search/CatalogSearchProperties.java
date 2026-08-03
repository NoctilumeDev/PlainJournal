package com.ecommerce.catalog.infrastructure.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties("ecommerce.catalog.search")
public record CatalogSearchProperties(
        boolean enabled,
        URI endpoint,
        String indexAlias,
        Duration connectTimeout,
        Duration requestTimeout,
        int batchSize,
        int maxAttempts,
        Duration retryDelay,
        Duration leaseDuration,
        String workerId,
        int rebuildBatchSize,
        int reconciliationLimit,
        boolean mysqlFallbackEnabled,
        boolean reconciliationEnabled,
        Duration reconciliationInitialDelay,
        Duration reconciliationFixedDelay
) {
    public CatalogSearchProperties {
        endpoint = endpoint == null ? URI.create("http://127.0.0.1:19200") : endpoint;
        indexAlias = normalizeIndexAlias(indexAlias);
        connectTimeout = positive(connectTimeout, Duration.ofMillis(500));
        requestTimeout = positive(requestTimeout, Duration.ofSeconds(2));
        batchSize = clamp(batchSize, 1, 200, 20);
        maxAttempts = clamp(maxAttempts, 1, 100, 5);
        retryDelay = positive(retryDelay, Duration.ofSeconds(2));
        leaseDuration = positive(leaseDuration, Duration.ofSeconds(30));
        workerId = workerId == null || workerId.isBlank() ? "catalog-search-local" : workerId.trim();
        rebuildBatchSize = clamp(rebuildBatchSize, 1, 1000, 200);
        reconciliationLimit = clamp(reconciliationLimit, 1, 50000, 20000);
        reconciliationInitialDelay = nonNegative(reconciliationInitialDelay, Duration.ofSeconds(30));
        reconciliationFixedDelay = positive(reconciliationFixedDelay, Duration.ofMinutes(5));
    }

    private static String normalizeIndexAlias(String value) {
        String normalized = value == null || value.isBlank()
                ? "plainjournal-products-local"
                : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Catalog search index alias is invalid");
        }
        return normalized;
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        return value < minimum ? fallback : Math.min(value, maximum);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration nonNegative(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}
