package com.ecommerce.fulfillment.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class FulfillmentSchedulingConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String OUTBOX_SCHEDULER = "fulfillmentOutboxScheduler";
    public static final String CONTROL_SCHEDULER = "fulfillmentControlScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler fulfillmentDefaultTaskScheduler(
            FulfillmentSchedulingProperties properties) {
        return scheduler(
                properties.defaultPoolSize(), "fulfillment-scheduling-", properties);
    }

    @Bean(name = OUTBOX_SCHEDULER)
    public ThreadPoolTaskScheduler fulfillmentOutboxTaskScheduler(
            FulfillmentSchedulingProperties properties) {
        return scheduler(
                properties.outboxPoolSize(), "fulfillment-outbox-scheduling-", properties);
    }

    @Bean(name = CONTROL_SCHEDULER)
    public ThreadPoolTaskScheduler fulfillmentControlTaskScheduler(
            FulfillmentSchedulingProperties properties) {
        return scheduler(
                properties.controlPoolSize(), "fulfillment-control-scheduling-", properties);
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize,
            String threadNamePrefix,
            FulfillmentSchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return scheduler;
    }
}
