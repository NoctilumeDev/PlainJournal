package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class OutboxMetrics {

    private final MeterRegistry registry;
    private final LongSupplier pendingCount;
    private final Supplier<Instant> oldestPendingCreatedAt;
    private final Clock clock;
    private final Timer publicationDuration;
    private final Counter publicationSucceeded;
    private final Counter publicationFailed;
    private final Counter publicationStateConflict;
    private final Counter claimContended;
    private final Counter staleClaimsRecovered;

    public OutboxMetrics(
            MeterRegistry registry,
            String service,
            LongSupplier pendingCount,
            Supplier<Instant> oldestPendingCreatedAt,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pendingCount = Objects.requireNonNull(pendingCount, "pendingCount");
        this.oldestPendingCreatedAt = Objects.requireNonNull(oldestPendingCreatedAt, "oldestPendingCreatedAt");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(service, "service");

        publicationDuration = Timer.builder("ecommerce.outbox.publish.duration")
                .description("Time spent publishing an outbox event and persisting its result")
                .tag("service", service)
                .register(registry);
        publicationSucceeded = publicationCounter(registry, service, "success");
        publicationFailed = publicationCounter(registry, service, "failure");
        publicationStateConflict = publicationCounter(registry, service, "state_conflict");
        claimContended = claimCounter(registry, service, "contended");
        staleClaimsRecovered = claimCounter(registry, service, "stale_recovered");

        Gauge.builder("ecommerce.outbox.pending", this, OutboxMetrics::readPendingCount)
                .description("Number of outbox events that have not reached PUBLISHED")
                .baseUnit("events")
                .tag("service", service)
                .register(registry);
        Gauge.builder("ecommerce.outbox.oldest.age", this, OutboxMetrics::readOldestPendingAgeSeconds)
                .description("Age in seconds of the oldest outbox event that has not reached PUBLISHED")
                .baseUnit("seconds")
                .tag("service", service)
                .register(registry);
    }

    public Timer.Sample startPublication() {
        return Timer.start(registry);
    }

    public void publicationSucceeded(Timer.Sample sample) {
        publicationSucceeded.increment();
        sample.stop(publicationDuration);
    }

    public void publicationFailed(Timer.Sample sample) {
        publicationFailed.increment();
        sample.stop(publicationDuration);
    }

    public void publicationStateConflict(Timer.Sample sample) {
        publicationStateConflict.increment();
        sample.stop(publicationDuration);
    }

    public void claimContended() {
        claimContended.increment();
    }

    public void staleClaimsRecovered(int count) {
        if (count > 0) {
            staleClaimsRecovered.increment(count);
        }
    }

    private double readPendingCount() {
        try {
            return pendingCount.getAsLong();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double readOldestPendingAgeSeconds() {
        try {
            Instant oldest = oldestPendingCreatedAt.get();
            if (oldest == null) {
                return 0;
            }
            return Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static Counter publicationCounter(MeterRegistry registry, String service, String outcome) {
        return Counter.builder("ecommerce.outbox.publications")
                .description("Outbox publication attempts by final local outcome")
                .tag("service", service)
                .tag("outcome", outcome)
                .register(registry);
    }

    private static Counter claimCounter(MeterRegistry registry, String service, String outcome) {
        return Counter.builder("ecommerce.outbox.claims")
                .description("Outbox claim coordination outcomes")
                .tag("service", service)
                .tag("outcome", outcome)
                .register(registry);
    }
}
