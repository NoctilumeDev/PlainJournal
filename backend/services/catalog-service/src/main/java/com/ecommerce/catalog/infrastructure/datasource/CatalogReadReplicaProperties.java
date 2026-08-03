package com.ecommerce.catalog.infrastructure.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.catalog.read-replica")
public class CatalogReadReplicaProperties {

    private boolean enabled;
    private boolean fallbackToPrimary = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFallbackToPrimary() {
        return fallbackToPrimary;
    }

    public void setFallbackToPrimary(boolean fallbackToPrimary) {
        this.fallbackToPrimary = fallbackToPrimary;
    }
}
