package com.ecommerce.fulfillment.infrastructure.observability;

import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentReconciliationMetrics {

    public FulfillmentReconciliationMetrics(MeterRegistry meterRegistry, ReconciliationRecordMapper mapper) {
        Gauge.builder("ecommerce.reconciliation.issue.open", mapper, ReconciliationRecordMapper::countOpen)
                .description("Open owner-domain reconciliation issues")
                .tag("service", "fulfillment-service")
                .register(meterRegistry);
    }
}
