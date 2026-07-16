package com.ecommerce.inventory.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ecommerce.inventory.infrastructure.messaging.OutboxProperties;
import com.ecommerce.inventory.infrastructure.messaging.OrderEventConsumerProperties;
import com.ecommerce.inventory.infrastructure.messaging.ReturnEventConsumerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        ReservationProperties.class,
        OutboxProperties.class,
        OrderEventConsumerProperties.class,
        ReturnEventConsumerProperties.class
})
public class InventoryInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
