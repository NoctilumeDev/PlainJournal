package com.ecommerce.catalog.infrastructure.datasource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class CatalogDataSourceMetrics {

    private final Counter primaryConnectionAttempts;
    private final Counter replicaConnectionAttempts;
    private final Counter replicaConnectionFailures;
    private final Counter replicaFallbacks;
    private final Counter primaryHints;

    CatalogDataSourceMetrics(MeterRegistry meterRegistry) {
        primaryConnectionAttempts = connectionCounter(meterRegistry, "primary");
        replicaConnectionAttempts = connectionCounter(meterRegistry, "replica");
        replicaConnectionFailures = Counter.builder(
                        "ecommerce.catalog.datasource.replica.connection.failures")
                .description("Catalog replica connection acquisition failures")
                .register(meterRegistry);
        replicaFallbacks = Counter.builder("ecommerce.catalog.datasource.replica.fallbacks")
                .description("Catalog read operations replayed once on the primary")
                .register(meterRegistry);
        primaryHints = Counter.builder("ecommerce.catalog.datasource.primary.hints")
                .description("Catalog requests explicitly requiring primary-read consistency")
                .register(meterRegistry);
    }

    void recordConnectionAttempt(boolean replica) {
        if (replica) {
            replicaConnectionAttempts.increment();
        } else {
            primaryConnectionAttempts.increment();
        }
    }

    void recordReplicaConnectionFailure() {
        replicaConnectionFailures.increment();
    }

    void recordReplicaFallback() {
        replicaFallbacks.increment();
    }

    void recordPrimaryHint() {
        primaryHints.increment();
    }

    private static Counter connectionCounter(MeterRegistry meterRegistry, String target) {
        return Counter.builder("ecommerce.catalog.datasource.connection.attempts")
                .description("Catalog datasource connection acquisition attempts")
                .tag("target", target)
                .register(meterRegistry);
    }
}
