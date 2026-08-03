package com.ecommerce.payment.infrastructure.reconciliation;

import com.ecommerce.payment.application.service.PaymentReconciliationService;
import com.ecommerce.payment.infrastructure.config.PaymentSchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final PaymentReconciliationService service;
    private final PaymentReconciliationProperties properties;

    public PaymentReconciliationJob(
            PaymentReconciliationService service,
            PaymentReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.payment.reconciliation.fixed-delay:10000}",
            initialDelayString = "${ecommerce.payment.reconciliation.initial-delay:10000}",
            scheduler = PaymentSchedulingConfig.CONTROL_SCHEDULER)
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        PaymentReconciliationService.ReconciliationScanResult result = service.reconcileNow();
        if (result.opened() > 0 || result.resolved() > 0 || result.saturated()) {
            log.warn("Payment reconciliation scan completed: findings={}, opened={}, resolved={}, saturated={}",
                    result.findings(), result.opened(), result.resolved(), result.saturated());
        } else if (result.findings() > 0) {
            log.debug("Payment reconciliation still has {} known findings", result.findings());
        }
    }
}
