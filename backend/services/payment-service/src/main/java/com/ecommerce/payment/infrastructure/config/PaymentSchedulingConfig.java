package com.ecommerce.payment.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class PaymentSchedulingConfig {

    public static final String DEFAULT_SCHEDULER = "taskScheduler";
    public static final String OUTBOX_SCHEDULER = "paymentOutboxScheduler";
    public static final String CONTROL_SCHEDULER = "paymentControlScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler paymentDefaultTaskScheduler(
            PaymentSchedulingProperties properties) {
        return scheduler(
                properties.defaultPoolSize(), "payment-scheduling-", properties);
    }

    @Bean(name = OUTBOX_SCHEDULER)
    public ThreadPoolTaskScheduler paymentOutboxTaskScheduler(
            PaymentSchedulingProperties properties) {
        return scheduler(
                properties.outboxPoolSize(), "payment-outbox-scheduling-", properties);
    }

    @Bean(name = CONTROL_SCHEDULER)
    public ThreadPoolTaskScheduler paymentControlTaskScheduler(
            PaymentSchedulingProperties properties) {
        return scheduler(
                properties.controlPoolSize(), "payment-control-scheduling-", properties);
    }

    private ThreadPoolTaskScheduler scheduler(
            int poolSize,
            String threadNamePrefix,
            PaymentSchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return scheduler;
    }
}
