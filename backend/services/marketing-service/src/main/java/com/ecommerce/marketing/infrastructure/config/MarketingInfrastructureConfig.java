package com.ecommerce.marketing.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ecommerce.marketing.infrastructure.messaging.OrderEventConsumerProperties;
import com.ecommerce.marketing.infrastructure.messaging.FlashSaleOutboxProperties;
import com.ecommerce.marketing.infrastructure.messaging.FlashSaleResultConsumerProperties;
import com.ecommerce.marketing.infrastructure.flashsale.FlashSaleAdmissionProperties;
import com.ecommerce.marketing.infrastructure.persistence.mapper.ConsumerFailureMapper;
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
        OrderEventConsumerProperties.class,
        FlashSaleAdmissionProperties.class,
        FlashSaleOutboxProperties.class,
        FlashSaleResultConsumerProperties.class,
        MarketingSchedulingProperties.class
})
public class MarketingInfrastructureConfig {

    @Bean
    public Clock marketingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MybatisPlusInterceptor marketingMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public ConsumerFailureObservability marketingConsumerFailureObservability(
            MeterRegistry meterRegistry,
            ConsumerFailureMapper mapper,
            Clock marketingClock) {
        return new ConsumerFailureObservability(
                meterRegistry, "marketing-service", mapper, marketingClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator marketingConsumerFailureRetryCoordinator(
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
                "marketing-service",
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
