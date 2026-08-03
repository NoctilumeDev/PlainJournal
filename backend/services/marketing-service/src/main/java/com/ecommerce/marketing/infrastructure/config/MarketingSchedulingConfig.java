package com.ecommerce.marketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MarketingSchedulingConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String OUTBOX_SCHEDULER = "marketingOutboxScheduler";
    public static final String CONTROL_SCHEDULER = "marketingControlScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler marketingDefaultTaskScheduler(
            MarketingSchedulingProperties properties) {
        return scheduler(
                properties.defaultPoolSize(), "marketing-scheduling-", properties);
    }

    @Bean(name = OUTBOX_SCHEDULER)
    public ThreadPoolTaskScheduler marketingOutboxTaskScheduler(
            MarketingSchedulingProperties properties) {
        return scheduler(
                properties.outboxPoolSize(), "marketing-outbox-scheduling-", properties);
    }

    @Bean(name = CONTROL_SCHEDULER)
    public ThreadPoolTaskScheduler marketingControlTaskScheduler(
            MarketingSchedulingProperties properties) {
        return scheduler(
                properties.controlPoolSize(), "marketing-control-scheduling-", properties);
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize,
            String threadNamePrefix,
            MarketingSchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return scheduler;
    }
}
