package com.ecommerce.fulfillment.infrastructure.config;

import com.ecommerce.fulfillment.infrastructure.messaging.AfterSaleEventConsumerProperties;
import com.ecommerce.fulfillment.infrastructure.messaging.OrderEventConsumerProperties;
import com.ecommerce.fulfillment.infrastructure.messaging.OutboxProperties;
import com.ecommerce.fulfillment.infrastructure.geo.ShipmentGeoProperties;
import com.ecommerce.fulfillment.infrastructure.reconciliation.FulfillmentReconciliationProperties;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        OutboxProperties.class,
        OrderEventConsumerProperties.class,
        AfterSaleEventConsumerProperties.class,
        ShipmentGeoProperties.class,
        FulfillmentSchedulingProperties.class,
        FulfillmentReconciliationProperties.class
})
public class FulfillmentInfrastructureConfig {

    @Bean
    public Clock fulfillmentClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConsumerFailureObservability fulfillmentConsumerFailureObservability(
            MeterRegistry meterRegistry, ConsumerFailureMapper mapper, Clock fulfillmentClock) {
        return new ConsumerFailureObservability(meterRegistry, "fulfillment-service", mapper, fulfillmentClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator fulfillmentConsumerFailureRetryCoordinator(
            ConsumerFailureMapper mapper,
            ConsumerFailureObservability observability,
            List<ConsumerFailureRetryHandler> handlers,
            @Value("${ecommerce.messaging.consumer-failure.max-delivery-attempts:16}")
            int maximumAttempts,
            @Value("${ecommerce.messaging.consumer-failure-retry.batch-size:20}")
            int batchSize,
            @Value("${ecommerce.messaging.consumer-failure-retry.retry-delay:PT15S}")
            Duration retryDelay,
            @Value("${ecommerce.messaging.consumer-failure-retry.lease-duration:PT30S}")
            Duration leaseDuration,
            @Value("${ecommerce.messaging.consumer-failure-retry.worker-id:}")
            String workerId) {
        return new ConsumerFailureRetryCoordinator(
                "fulfillment-service",
                mapper,
                observability,
                maximumAttempts,
                batchSize,
                retryDelay,
                leaseDuration,
                workerId,
                handlers);
    }
}
