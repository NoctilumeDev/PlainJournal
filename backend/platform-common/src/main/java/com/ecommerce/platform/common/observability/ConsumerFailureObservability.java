package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Endpoint(id = "consumerfailures")
public final class ConsumerFailureObservability {

    private static final String RETRYING = "RETRYING";
    private static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
    private static final String RECOVERED = "RECOVERED";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final String service;
    private final ConsumerFailureStore store;
    private final Clock clock;
    private final Counter retryingTransitions;
    private final Counter needsAttentionTransitions;
    private final Counter recoveredTransitions;

    public ConsumerFailureObservability(
            MeterRegistry registry,
            String service,
            ConsumerFailureStore store,
            Clock clock) {
        Objects.requireNonNull(registry, "registry");
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");

        retryingTransitions = transitionCounter(registry, service, "retrying");
        needsAttentionTransitions = transitionCounter(registry, service, "needs_attention");
        recoveredTransitions = transitionCounter(registry, service, "recovered");

        activeGauge(registry, service, "retrying", RETRYING);
        activeGauge(registry, service, "needs_attention", NEEDS_ATTENTION);
        Gauge.builder("ecommerce.consumer.failure.oldest.age", this,
                        ConsumerFailureObservability::readOldestActiveAgeSeconds)
                .description("Age in seconds of the oldest consumer failure that is not recovered")
                .baseUnit("seconds")
                .tag("service", service)
                .register(registry);
    }

    @ReadOperation
    public ConsumerFailureReport failures(@Nullable Integer limit) {
        int boundedLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        Instant oldest = store.selectOldestActiveFailedAt();
        return new ConsumerFailureReport(
                service,
                clock.instant(),
                store.countByStatus(RETRYING),
                store.countByStatus(NEEDS_ATTENTION),
                store.countByStatus(RECOVERED),
                ageSeconds(oldest),
                List.copyOf(store.selectRecentActive(boundedLimit)));
    }

    public void failureRecorded(boolean terminal) {
        if (terminal) {
            needsAttentionTransitions.increment();
        } else {
            retryingTransitions.increment();
        }
    }

    public void recovered() {
        recoveredTransitions.increment();
    }

    private void activeGauge(MeterRegistry registry, String service, String statusTag, String status) {
        Gauge.builder("ecommerce.consumer.failure.active", this, ignored -> readCount(status))
                .description("Consumer failures that have not recovered, grouped by operational status")
                .baseUnit("events")
                .tag("service", service)
                .tag("status", statusTag)
                .register(registry);
    }

    private double readCount(String status) {
        try {
            return store.countByStatus(status);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double readOldestActiveAgeSeconds() {
        try {
            return ageSeconds(store.selectOldestActiveFailedAt());
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

    private static Counter transitionCounter(MeterRegistry registry, String service, String outcome) {
        return Counter.builder("ecommerce.consumer.failure.transitions")
                .description("Consumer failure record transitions by outcome")
                .tag("service", service)
                .tag("outcome", outcome)
                .register(registry);
    }

    public record ConsumerFailureReport(
            String service,
            Instant generatedAt,
            long retrying,
            long needsAttention,
            long recovered,
            double oldestActiveAgeSeconds,
            List<ConsumerFailureEntry> activeFailures) {
    }
}
