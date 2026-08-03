package com.ecommerce.platform.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsScrapePropertiesTest {

    @Test
    void keepsMetricsIdentityDisabledWhenNoTokenIsConfigured() {
        assertThat(new MetricsScrapeProperties(null).enabled()).isFalse();
        assertThat(new MetricsScrapeProperties("   ").enabled()).isFalse();
    }

    @Test
    void acceptsASeparateStrongScrapeToken() {
        MetricsScrapeProperties properties = new MetricsScrapeProperties(
                "metrics-scrape-token-with-more-than-32-characters");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.token()).isEqualTo("metrics-scrape-token-with-more-than-32-characters");
    }

    @Test
    void rejectsWeakConfiguredTokens() {
        assertThatThrownBy(() -> new MetricsScrapeProperties("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 characters");
    }
}
