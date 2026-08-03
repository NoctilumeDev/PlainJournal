package com.ecommerce.inventory.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ecommerce.inventory.infrastructure.messaging.OutboxProperties;
import com.ecommerce.inventory.infrastructure.messaging.OrderEventConsumerProperties;
import com.ecommerce.inventory.infrastructure.messaging.ReturnEventConsumerProperties;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.inventory.infrastructure.reconciliation.InventoryReconciliationProperties;
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
        ReservationProperties.class,
        InventorySchedulingProperties.class,
        OutboxProperties.class,
        OrderEventConsumerProperties.class,
        ReturnEventConsumerProperties.class,
        InventoryReconciliationProperties.class
})
public class InventoryInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConsumerFailureObservability inventoryConsumerFailureObservability(
            MeterRegistry meterRegistry, ConsumerFailureMapper mapper, Clock clock) {
        return new ConsumerFailureObservability(meterRegistry, "inventory-service", mapper, clock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator inventoryConsumerFailureRetryCoordinator(
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
                "inventory-service",
                mapper,
                observability,
                maximumAttempts,
                batchSize,
                retryDelay,
                leaseDuration,
                workerId,
                handlers);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
