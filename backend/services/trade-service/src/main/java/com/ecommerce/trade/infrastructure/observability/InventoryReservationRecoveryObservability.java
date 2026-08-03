package com.ecommerce.trade.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservationRecoveryObservability {

    private static final String METRIC_NAME =
            "ecommerce.trade.inventory.reservation.unknown.result.resolutions";

    private final Counter recovered;
    private final Counter unresolved;

    public InventoryReservationRecoveryObservability(MeterRegistry meterRegistry) {
        this.recovered = resolutionCounter(meterRegistry, "recovered");
        this.unresolved = resolutionCounter(meterRegistry, "unresolved");
    }

    public void recordRecovered() {
        recovered.increment();
    }

    public void recordUnresolved() {
        unresolved.increment();
    }

    private Counter resolutionCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(METRIC_NAME)
                .description("Resolution outcomes after an inventory reservation response is unknown")
                .tag("service", "trade-service")
                .tag("dependency", "inventory-service")
                .tag("operation", "reserve")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
