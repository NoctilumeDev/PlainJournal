package com.ecommerce.payment.infrastructure.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.payment.reconciliation")
public record PaymentReconciliationProperties(
        boolean enabled,
        long fixedDelay,
        long initialDelay,
        int scanLimit
) {
    public PaymentReconciliationProperties {
        fixedDelay = fixedDelay <= 0 ? 10000 : fixedDelay;
        initialDelay = initialDelay < 0 ? 0 : initialDelay;
        scanLimit = scanLimit <= 0 ? 500 : Math.min(scanLimit, 5000);
    }
}
