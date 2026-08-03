package com.ecommerce.trade;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties = "ecommerce.trade.client.service-discovery-enabled=false")
class TradeDirectClientConfigurationTest {

    private final ApplicationContext applicationContext;

    @Autowired
    TradeDirectClientConfigurationTest(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void selectsDirectBuildersForEverySynchronousDependency() {
        assertThat(applicationContext.containsBean("tradeDirectRestClientBuilder")).isTrue();
        assertThat(applicationContext.containsBean("tradeRestClientBuilder")).isFalse();
        assertThat(applicationContext.containsBean("tradeMarketingDirectRestClientBuilder")).isTrue();
        assertThat(applicationContext.containsBean("tradeMarketingLoadBalancedRestClientBuilder")).isFalse();
    }
}
