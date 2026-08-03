package com.ecommerce.payment.infrastructure.observability;

import com.ecommerce.payment.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PaymentReconciliationMetrics {

    public PaymentReconciliationMetrics(
            MeterRegistry meterRegistry,
            ReconciliationRecordMapper mapper) {
        Gauge.builder("ecommerce.reconciliation.issue.open", mapper, ReconciliationRecordMapper::countOpen)
                .description("Open owner-domain reconciliation issues")
                .tag("service", "payment-service")
                .register(meterRegistry);
    }
}
