package com.ecommerce.inventory.infrastructure.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.inventory.reconciliation")
public record InventoryReconciliationProperties(
        boolean enabled,
        long fixedDelay,
        long initialDelay,
        int scanLimit
) {
    public InventoryReconciliationProperties {
        fixedDelay = fixedDelay <= 0 ? 10000 : fixedDelay;
        initialDelay = initialDelay < 0 ? 0 : initialDelay;
        scanLimit = scanLimit <= 0 ? 500 : Math.min(scanLimit, 5000);
    }
}
