package com.ecommerce.trade.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.ecommerce.trade.infrastructure.messaging.OutboxProperties;
import com.ecommerce.trade.infrastructure.messaging.PaymentEventConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.FulfillmentEventConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.AfterSaleFulfillmentConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.AfterSaleInventoryConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.RefundResultConsumerProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        OrderProperties.class,
        InternalClientProperties.class,
        RemoteClientProperties.class,
        OutboxProperties.class,
        PaymentEventConsumerProperties.class,
        FulfillmentEventConsumerProperties.class,
        AfterSaleFulfillmentConsumerProperties.class,
        AfterSaleInventoryConsumerProperties.class,
        RefundResultConsumerProperties.class
})
public class TradeInfrastructureConfig {

    @Bean
    public Clock tradeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MybatisPlusInterceptor tradeMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder tradeRestClientBuilder(RemoteClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
