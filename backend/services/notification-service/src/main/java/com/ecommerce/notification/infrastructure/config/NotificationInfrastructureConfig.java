package com.ecommerce.notification.infrastructure.config;

import com.ecommerce.notification.infrastructure.persistence.NotificationRepository;
import com.ecommerce.notification.infrastructure.security.NotificationTokenProperties;
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
        NotificationDeliveryProperties.class,
        NotificationEventConsumerProperties.class,
        NotificationTokenProperties.class,
        MetricsScrapeProperties.class
})
public class NotificationInfrastructureConfig {

    @Bean
    public Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConsumerFailureObservability notificationConsumerFailureObservability(
            MeterRegistry registry,
            NotificationRepository repository,
            Clock notificationClock) {
        return new ConsumerFailureObservability(
                registry,
                "notification-service",
                repository,
                notificationClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator notificationConsumerFailureRetryCoordinator(
            NotificationRepository repository,
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
                "notification-service",
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
