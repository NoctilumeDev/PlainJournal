package com.ecommerce.trade.infrastructure.observability;

import com.ecommerce.trade.application.service.TradeReconciliationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TradeReconciliationMetrics {

    public TradeReconciliationMetrics(
            MeterRegistry meterRegistry,
            TradeReconciliationService reconciliationService) {
        Gauge.builder(
                        "ecommerce.reconciliation.issue.open",
                        reconciliationService,
                        TradeReconciliationService::countOpenIssues)
                .description("Open owner-domain reconciliation issues")
                .tag("service", "trade-service")
                .register(meterRegistry);
    }
}
