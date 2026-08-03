package com.ecommerce.trade.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TradeSchedulingConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String ORDER_RECOVERY_SCHEDULER = "tradeOrderRecoveryScheduler";
    public static final String OUTBOX_SCHEDULER = "tradeOutboxScheduler";
    public static final String FLASH_SALE_SCHEDULER = "tradeFlashSaleScheduler";
    public static final String CONSUMER_FAILURE_SCHEDULER = "tradeConsumerFailureScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler tradeDefaultTaskScheduler(TradeSchedulingProperties properties) {
        return scheduler(properties.defaultPoolSize(), "trade-scheduling-", properties);
    }

    @Bean(name = ORDER_RECOVERY_SCHEDULER)
    public ThreadPoolTaskScheduler tradeOrderRecoveryScheduler(TradeSchedulingProperties properties) {
        return scheduler(properties.orderRecoveryPoolSize(), "trade-order-recovery-", properties);
    }

    @Bean(name = OUTBOX_SCHEDULER)
    public ThreadPoolTaskScheduler tradeOutboxScheduler(TradeSchedulingProperties properties) {
        return scheduler(properties.outboxPoolSize(), "trade-outbox-scheduling-", properties);
    }

    @Bean(name = FLASH_SALE_SCHEDULER)
    public ThreadPoolTaskScheduler tradeFlashSaleScheduler(TradeSchedulingProperties properties) {
        return scheduler(properties.flashSalePoolSize(), "trade-flash-sale-", properties);
    }

    @Bean(name = CONSUMER_FAILURE_SCHEDULER)
    public ThreadPoolTaskScheduler tradeConsumerFailureScheduler(TradeSchedulingProperties properties) {
        return scheduler(
                properties.consumerFailurePoolSize(),
                "trade-consumer-failure-",
                properties);
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize,
            String threadNamePrefix,
            TradeSchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return scheduler;
    }
}
