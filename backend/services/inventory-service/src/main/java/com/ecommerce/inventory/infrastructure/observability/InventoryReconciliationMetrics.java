package com.ecommerce.inventory.infrastructure.observability;

import com.ecommerce.inventory.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class InventoryReconciliationMetrics {

    public InventoryReconciliationMetrics(
            MeterRegistry meterRegistry,
            ReconciliationRecordMapper mapper) {
        Gauge.builder("ecommerce.reconciliation.issue.open", mapper, ReconciliationRecordMapper::countOpen)
                .description("Open owner-domain reconciliation issues")
                .tag("service", "inventory-service")
                .register(meterRegistry);
    }
}
