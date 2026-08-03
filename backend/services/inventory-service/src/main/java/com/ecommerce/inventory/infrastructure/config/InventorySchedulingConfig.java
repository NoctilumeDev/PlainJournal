package com.ecommerce.inventory.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class InventorySchedulingConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String OUTBOX_SCHEDULER = "inventoryOutboxScheduler";
    public static final String CONTROL_SCHEDULER = "inventoryControlScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler inventoryDefaultTaskScheduler(
            InventorySchedulingProperties properties) {
        return scheduler(properties.defaultPoolSize(), "inventory-scheduling-", properties);
    }

    @Bean(name = OUTBOX_SCHEDULER)
    public ThreadPoolTaskScheduler inventoryOutboxTaskScheduler(
            InventorySchedulingProperties properties) {
        return scheduler(properties.outboxPoolSize(), "inventory-outbox-scheduling-", properties);
    }

    @Bean(name = CONTROL_SCHEDULER)
    public ThreadPoolTaskScheduler inventoryControlTaskScheduler(
            InventorySchedulingProperties properties) {
        return scheduler(properties.controlPoolSize(), "inventory-control-scheduling-", properties);
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize,
            String threadNamePrefix,
            InventorySchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return scheduler;
    }
}
