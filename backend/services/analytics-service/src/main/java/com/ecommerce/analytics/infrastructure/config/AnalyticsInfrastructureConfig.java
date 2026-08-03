package com.ecommerce.analytics.infrastructure.config;

import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository;
import com.ecommerce.analytics.infrastructure.security.AnalyticsTokenProperties;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.platform.common.security.MetricsScrapeProperties;
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
        AnalyticsProperties.class,
        AnalyticsEventConsumerProperties.class,
        AnalyticsTokenProperties.class,
        MetricsScrapeProperties.class
})
public class AnalyticsInfrastructureConfig {

    @Bean
    public Clock analyticsClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConsumerFailureObservability analyticsConsumerFailureObservability(
            MeterRegistry registry,
            AnalyticsRepository repository,
            Clock analyticsClock) {
        return new ConsumerFailureObservability(
                registry,
                "analytics-service",
                repository,
                analyticsClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator analyticsConsumerFailureRetryCoordinator(
            AnalyticsRepository repository,
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
                "analytics-service",
                repository,
                observability,
                maximumAttempts,
                batchSize,
                retryDelay,
                leaseDuration,
                workerId,
                handlers);
    }
}
