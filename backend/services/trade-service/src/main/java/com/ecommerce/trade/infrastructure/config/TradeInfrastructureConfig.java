package com.ecommerce.trade.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.platform.common.observability.BusinessProcessObservability;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.trade.infrastructure.id.DistributedIdWorkerLeaseManager;
import com.ecommerce.trade.infrastructure.id.DistributedIdWorkerLeaseStore;
import com.ecommerce.trade.infrastructure.observability.TradeBusinessProcessStore;
import com.ecommerce.trade.infrastructure.persistence.mapper.ConsumerFailureMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import com.ecommerce.trade.infrastructure.messaging.OutboxProperties;
import com.ecommerce.trade.infrastructure.messaging.PaymentEventConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.ProcessTerminationFaultProperties;
import com.ecommerce.trade.infrastructure.messaging.FulfillmentEventConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.AfterSaleFulfillmentConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.AfterSaleInventoryConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.RefundResultConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.FlashSaleConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.TradeConsumerFailureRetryStore;
import com.ecommerce.trade.infrastructure.reconciliation.TradeReconciliationProperties;
import com.ecommerce.trade.infrastructure.sharding.HintTradeShardRouter;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import com.ecommerce.trade.infrastructure.sharding.UnshardedTradeShardRouter;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        OrderProperties.class,
        AfterSaleProperties.class,
        InternalClientProperties.class,
        RemoteClientProperties.class,
        SynchronousBoundaryResilienceProperties.class,
        MarketingPricingLockResilienceProperties.class,
        TradeSchedulingProperties.class,
        OutboxProperties.class,
        ProcessTerminationFaultProperties.class,
        PaymentEventConsumerProperties.class,
        FulfillmentEventConsumerProperties.class,
        AfterSaleFulfillmentConsumerProperties.class,
        AfterSaleInventoryConsumerProperties.class,
        RefundResultConsumerProperties.class,
        FlashSaleConsumerProperties.class,
        TradeReconciliationProperties.class,
        DistributedIdProperties.class,
        TradeShardingProperties.class
})
public class TradeInfrastructureConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.sharding",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public TradeShardRouter unshardedTradeShardRouter() {
        return new UnshardedTradeShardRouter();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.sharding",
            name = "enabled",
            havingValue = "true")
    public TradeShardRouter shardedTradeShardRouter(TradeShardingProperties properties) {
        return new HintTradeShardRouter(properties.getShards().size());
    }

    @Bean
    public Clock tradeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DistributedIdWorkerLeaseManager tradeDistributedIdWorkerLeaseManager(
            DistributedIdWorkerLeaseStore store,
            DistributedIdProperties properties) {
        return new DistributedIdWorkerLeaseManager(store, properties);
    }

    @Bean
    public DistributedIdGenerator tradeOrderIdGenerator(
            DistributedIdProperties properties,
            DistributedIdWorkerLeaseManager leaseManager,
            Clock tradeClock) {
        return new DistributedIdGenerator(
                leaseManager.workerId(),
                properties.epoch().toEpochMilli(),
                tradeClock::millis,
                leaseManager::isOwned);
    }

    @Bean
    public ConsumerFailureObservability tradeConsumerFailureObservability(
            MeterRegistry meterRegistry, ConsumerFailureMapper mapper, Clock tradeClock) {
        return new ConsumerFailureObservability(meterRegistry, "trade-service", mapper, tradeClock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.messaging.consumer-failure-retry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ConsumerFailureRetryCoordinator tradeConsumerFailureRetryCoordinator(
            TradeConsumerFailureRetryStore store,
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
                "trade-service",
                store,
                observability,
                maximumAttempts,
                batchSize,
                retryDelay,
                leaseDuration,
                workerId,
                handlers);
    }

    @Bean
    public BusinessProcessObservability tradeBusinessProcessObservability(
            MeterRegistry meterRegistry, TradeBusinessProcessStore store, Clock tradeClock) {
        return new BusinessProcessObservability(meterRegistry, "trade-service", store, tradeClock);
    }

    @Bean
    public MessagingTracing tradeMessagingTracing(Tracer tracer, Propagator propagator) {
        return new MessagingTracing(tracer, propagator);
    }

    @Bean
    public MybatisPlusInterceptor tradeMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    @LoadBalanced
    @Primary
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.client",
            name = "service-discovery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RestClient.Builder tradeRestClientBuilder(
            RemoteClientProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties.connectTimeout(), properties.readTimeout(), configurer);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.client",
            name = "service-discovery-enabled",
            havingValue = "false")
    public RestClient.Builder tradeDirectRestClientBuilder(
            RemoteClientProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties.connectTimeout(), properties.readTimeout(), configurer);
    }

    @Bean
    @LoadBalanced
    @Qualifier("tradeMarketingRestClientBuilder")
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.client",
            name = "service-discovery-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RestClient.Builder tradeMarketingLoadBalancedRestClientBuilder(
            MarketingPricingLockResilienceProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties.connectTimeout(), properties.readTimeout(), configurer);
    }

    @Bean
    @Qualifier("tradeMarketingRestClientBuilder")
    @ConditionalOnProperty(
            prefix = "ecommerce.trade.client",
            name = "service-discovery-enabled",
            havingValue = "false")
    public RestClient.Builder tradeMarketingDirectRestClientBuilder(
            MarketingPricingLockResilienceProperties properties,
            RestClientBuilderConfigurer configurer) {
        return restClientBuilder(properties.connectTimeout(), properties.readTimeout(), configurer);
    }

    private RestClient.Builder restClientBuilder(
            Duration connectTimeout,
            Duration readTimeout,
            RestClientBuilderConfigurer configurer) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory);
    }
}
