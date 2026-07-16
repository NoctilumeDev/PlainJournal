package com.ecommerce.fulfillment.infrastructure.config;

import com.ecommerce.fulfillment.infrastructure.messaging.AfterSaleEventConsumerProperties;
import com.ecommerce.fulfillment.infrastructure.messaging.OrderEventConsumerProperties;
import com.ecommerce.fulfillment.infrastructure.messaging.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        OutboxProperties.class,
        OrderEventConsumerProperties.class,
        AfterSaleEventConsumerProperties.class
})
public class FulfillmentInfrastructureConfig {

    @Bean
    public Clock fulfillmentClock() {
        return Clock.systemUTC();
    }
}
