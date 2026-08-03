package com.ecommerce.analytics.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AnalyticsObservability {

    private static final String SERVICE = "analytics-service";

    private final MeterRegistry registry;
    private final Map<String, Counter> eventCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> rebuildCounters = new ConcurrentHashMap<>();
    private final AtomicLong reconciliationIssues = new AtomicLong();

    public AnalyticsObservability(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "ecommerce.analytics.reconciliation.issues",
                        reconciliationIssues,
                        AtomicLong::doubleValue)
                .description("Issues found by the latest bounded analytics reconciliation")
                .baseUnit("issues")
                .tag("service", SERVICE)
                .register(registry);
    }

    public void eventAccepted(String eventType) {
        eventCounter(eventType, "accepted").increment();
    }

    public void eventDuplicate(String eventType) {
        eventCounter(eventType, "duplicate").increment();
    }

    public void reconciliationCompleted(long issueCount) {
        reconciliationIssues.set(Math.max(0, issueCount));
    }

    public void rebuildCompleted(boolean success) {
        String outcome = success ? "success" : "failure";
        rebuildCounters.computeIfAbsent(outcome, key -> Counter.builder("ecommerce.analytics.rebuilds")
                        .description("Analytics projection rebuild commands by outcome")
                        .tag("service", SERVICE)
                        .tag("outcome", key)
                        .register(registry))
                .increment();
    }

    private Counter eventCounter(String eventType, String outcome) {
        String key = eventType + ':' + outcome;
        return eventCounters.computeIfAbsent(key, ignored -> Counter.builder("ecommerce.analytics.events")
                .description("Analytics source events by bounded type and processing outcome")
                .tag("service", SERVICE)
                .tag("event_type", eventType)
                .tag("outcome", outcome)
                .register(registry));
    }
}
