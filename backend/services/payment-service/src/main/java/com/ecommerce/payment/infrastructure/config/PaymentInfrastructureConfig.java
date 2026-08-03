package com.ecommerce.payment.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ecommerce.payment.infrastructure.messaging.OutboxProperties;
import com.ecommerce.payment.infrastructure.messaging.RefundEventConsumerProperties;
import com.ecommerce.payment.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.payment.infrastructure.refund.RefundDispatchProperties;
import com.ecommerce.payment.infrastructure.reconciliation.PaymentReconciliationProperties;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.platform.common.observability.BusinessProcessObservability;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.payment.infrastructure.observability.PaymentBusinessProcessStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        PaymentClientProperties.class,
        InternalClientProperties.class,
        MockChannelProperties.class,
        OutboxProperties.class,
        RefundEventConsumerProperties.class,
        RefundDispatchProperties.class,
        PaymentReconciliationProperties.class,
        PaymentSchedulingProperties.class,
        TradePaymentContextResilienceProperties.class
})
public class PaymentInfrastructureConfig {

    @Bean
    public Clock paymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConsumerFailureObservability paymentConsumerFailureObservability(
            MeterRegistry meterRegistry, ConsumerFailureMapper mapper, Clock paymentClock) {
        return new ConsumerFailureObservability(meterRegistry, "payment-service", mapper, paymentClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator paymentConsumerFailureRetryCoordinator(
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
                "payment-service",
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
    public BusinessProcessObservability paymentBusinessProcessObservability(
            MeterRegistry meterRegistry, PaymentBusinessProcessStore store, Clock paymentClock) {
        return new BusinessProcessObservability(meterRegistry, "payment-service", store, paymentClock);
    }

    @Bean
    public MessagingTracing paymentMessagingTracing(Tracer tracer, Propagator propagator) {
        return new MessagingTracing(tracer, propagator);
    }

    @Bean
    public MybatisPlusInterceptor paymentMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    @LoadBalanced
    @Primary
    @ConditionalOnProperty(
            prefix = "ecommerce.payment.client",
            name = "service-discovery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RestClient.Builder paymentRestClientBuilder(
            PaymentClientProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties, configurer);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ecommerce.payment.client",
            name = "service-discovery-enabled",
            havingValue = "false")
    public RestClient.Builder paymentDirectRestClientBuilder(
            PaymentClientProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties, configurer);
    }

    private RestClient.Builder restClientBuilder(
            PaymentClientProperties properties,
            RestClientBuilderConfigurer configurer) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory);
    }
}
