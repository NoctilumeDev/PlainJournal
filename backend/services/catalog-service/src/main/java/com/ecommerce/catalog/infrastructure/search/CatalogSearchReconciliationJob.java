package com.ecommerce.catalog.infrastructure.search;

import com.ecommerce.catalog.application.service.CatalogSearchReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.catalog.search",
        name = {"enabled", "reconciliation-enabled"},
        havingValue = "true")
public class CatalogSearchReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogSearchReconciliationJob.class);

    private final CatalogSearchReconciliationService service;

    public CatalogSearchReconciliationJob(CatalogSearchReconciliationService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.catalog.search.reconciliation-initial-delay:30000}",
            fixedDelayString = "${ecommerce.catalog.search.reconciliation-fixed-delay:300000}",
            scheduler = "catalogSearchScheduler")
    public void reconcile() {
        try {
            var result = service.reconcile(true);
            if (result.missing() > 0 || result.stale() > 0 || result.orphan() > 0 || result.saturated()) {
                log.warn("Catalog search reconciliation found divergence: missing={}, stale={}, orphan={}, saturated={}",
                        result.missing(), result.stale(), result.orphan(), result.saturated());
            }
        } catch (RuntimeException exception) {
            log.warn("Catalog search reconciliation could not complete; no healthy result was fabricated");
        }
    }
}
