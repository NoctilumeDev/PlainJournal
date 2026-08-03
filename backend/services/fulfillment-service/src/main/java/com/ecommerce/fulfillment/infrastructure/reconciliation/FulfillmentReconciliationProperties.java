package com.ecommerce.fulfillment.infrastructure.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.fulfillment.reconciliation")
public record FulfillmentReconciliationProperties(
        boolean enabled,
        long fixedDelay,
        long initialDelay,
        int scanLimit
) {
    public FulfillmentReconciliationProperties {
        fixedDelay = fixedDelay <= 0 ? 10000 : fixedDelay;
        initialDelay = initialDelay < 0 ? 0 : initialDelay;
        scanLimit = scanLimit <= 0 ? 500 : Math.min(scanLimit, 5000);
    }
}
