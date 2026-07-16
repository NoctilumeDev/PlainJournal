package com.ecommerce.marketing.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ecommerce.marketing.infrastructure.messaging.OrderEventConsumerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(OrderEventConsumerProperties.class)
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
}
