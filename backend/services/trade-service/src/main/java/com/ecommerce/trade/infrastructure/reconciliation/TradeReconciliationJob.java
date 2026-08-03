package com.ecommerce.trade.infrastructure.reconciliation;

import com.ecommerce.trade.application.service.TradeReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradeReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(TradeReconciliationJob.class);

    private final TradeReconciliationService service;
    private final TradeReconciliationProperties properties;

    public TradeReconciliationJob(
            TradeReconciliationService service,
            TradeReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.trade.reconciliation.fixed-delay:10000}",
            initialDelayString = "${ecommerce.trade.reconciliation.initial-delay:10000}")
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        TradeReconciliationService.ReconciliationScanResult result = service.reconcileNow();
        if (result.opened() > 0 || result.resolved() > 0 || result.saturated()) {
            log.warn("Trade reconciliation scan completed: findings={}, opened={}, resolved={}, saturated={}",
                    result.findings(), result.opened(), result.resolved(), result.saturated());
        } else if (result.findings() > 0) {
            log.debug("Trade reconciliation still has {} known findings", result.findings());
        }
    }
}
