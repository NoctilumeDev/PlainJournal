package com.ecommerce.inventory.infrastructure.reconciliation;

import com.ecommerce.inventory.application.service.InventoryReconciliationService;
import com.ecommerce.inventory.infrastructure.config.InventorySchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationJob.class);

    private final InventoryReconciliationService service;
    private final InventoryReconciliationProperties properties;

    public InventoryReconciliationJob(
            InventoryReconciliationService service,
            InventoryReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.inventory.reconciliation.fixed-delay:10000}",
            initialDelayString = "${ecommerce.inventory.reconciliation.initial-delay:10000}",
            scheduler = InventorySchedulingConfig.CONTROL_SCHEDULER)
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        InventoryReconciliationService.ReconciliationScanResult result = service.reconcileNow();
        if (result.opened() > 0 || result.resolved() > 0 || result.saturated()) {
            log.warn("Inventory reconciliation scan completed: findings={}, opened={}, resolved={}, saturated={}",
                    result.findings(), result.opened(), result.resolved(), result.saturated());
        } else if (result.findings() > 0) {
            log.debug("Inventory reconciliation still has {} known findings", result.findings());
        }
    }
}
