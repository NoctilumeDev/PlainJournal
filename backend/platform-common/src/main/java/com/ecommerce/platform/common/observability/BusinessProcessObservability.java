package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Endpoint(id = "businessprocesses")
public final class BusinessProcessObservability {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final String service;
    private final BusinessProcessStore store;
    private final Clock clock;

    public BusinessProcessObservability(
            MeterRegistry registry,
            String service,
            BusinessProcessStore store,
            Clock clock) {
        Objects.requireNonNull(registry, "registry");
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");

        for (BusinessProcessDefinition definition : store.definitions()) {
            registerCountGauge(registry, definition);
            registerAgeGauge(registry, definition);
        }
    }

    @ReadOperation
    public BusinessProcessReport processes(@Nullable Integer limit) {
        int boundedLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        List<BusinessProcessState> states = store.definitions().stream()
                .map(definition -> new BusinessProcessState(
                        definition.domain(),
                        definition.status(),
                        store.count(definition),
                        ageSeconds(store.oldestUpdatedAt(definition))))
                .toList();
        return new BusinessProcessReport(
                service,
                clock.instant(),
                states,
                List.copyOf(store.selectOldestActive(boundedLimit)));
    }

    private void registerCountGauge(MeterRegistry registry, BusinessProcessDefinition definition) {
        Gauge.builder("ecommerce.business.process.active", definition, this::readCount)
                .description("Active business processes grouped by owner domain and status")
                .baseUnit("processes")
                .tag("service", service)
                .tag("domain", normalized(definition.domain()))
                .tag("status", normalized(definition.status()))
                .register(registry);
    }

    private void registerAgeGauge(MeterRegistry registry, BusinessProcessDefinition definition) {
        Gauge.builder("ecommerce.business.process.oldest.age", definition, this::readAge)
                .description("Age in seconds of the oldest active business process for a monitored status")
                .baseUnit("seconds")
                .tag("service", service)
                .tag("domain", normalized(definition.domain()))
                .tag("status", normalized(definition.status()))
                .register(registry);
    }

    private double readCount(BusinessProcessDefinition definition) {
        try {
            return store.count(definition);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double readAge(BusinessProcessDefinition definition) {
        try {
            return ageSeconds(store.oldestUpdatedAt(definition));
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double ageSeconds(Instant oldest) {
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }

    private static String normalized(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record BusinessProcessReport(
            String service,
            Instant generatedAt,
            List<BusinessProcessState> states,
            List<BusinessProcessEntry> activeProcesses) {
    }

    public record BusinessProcessState(
            String domain,
            String status,
            long count,
            double oldestAgeSeconds) {
    }
}
