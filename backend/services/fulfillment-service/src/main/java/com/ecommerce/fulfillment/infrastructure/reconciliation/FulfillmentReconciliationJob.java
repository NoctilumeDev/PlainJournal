package com.ecommerce.fulfillment.infrastructure.reconciliation;

import com.ecommerce.fulfillment.application.service.FulfillmentReconciliationService;
import com.ecommerce.fulfillment.infrastructure.config.FulfillmentSchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentReconciliationJob.class);

    private final FulfillmentReconciliationService service;
    private final FulfillmentReconciliationProperties properties;

    public FulfillmentReconciliationJob(
            FulfillmentReconciliationService service,
            FulfillmentReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.fulfillment.reconciliation.fixed-delay:10000}",
            initialDelayString = "${ecommerce.fulfillment.reconciliation.initial-delay:10000}",
            scheduler = FulfillmentSchedulingConfig.CONTROL_SCHEDULER)
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        FulfillmentReconciliationService.ReconciliationScanResult result = service.reconcileNow();
        if (result.opened() > 0 || result.resolved() > 0 || result.saturated()) {
            log.warn("Fulfillment reconciliation scan completed: findings={}, opened={}, resolved={}, saturated={}",
                    result.findings(), result.opened(), result.resolved(), result.saturated());
        } else if (result.findings() > 0) {
            log.debug("Fulfillment reconciliation still has {} known findings", result.findings());
        }
    }
}
