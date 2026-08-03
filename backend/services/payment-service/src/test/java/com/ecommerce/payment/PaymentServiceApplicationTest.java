package com.ecommerce.payment;

import com.ecommerce.payment.infrastructure.config.PaymentSchedulingConfig;
import com.ecommerce.payment.infrastructure.resilience.PaymentTradeResilience;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class PaymentServiceApplicationTest {

    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskScheduler defaultScheduler;
    private final ThreadPoolTaskScheduler outboxScheduler;
    private final ThreadPoolTaskScheduler controlScheduler;

    @Autowired
    PaymentServiceApplicationTest(
            MeterRegistry meterRegistry,
            @Qualifier(PaymentSchedulingConfig.DEFAULT_SCHEDULER)
            ThreadPoolTaskScheduler defaultScheduler,
            @Qualifier(PaymentSchedulingConfig.OUTBOX_SCHEDULER)
            ThreadPoolTaskScheduler outboxScheduler,
            @Qualifier(PaymentSchedulingConfig.CONTROL_SCHEDULER)
            ThreadPoolTaskScheduler controlScheduler) {
        this.meterRegistry = meterRegistry;
        this.defaultScheduler = defaultScheduler;
        this.outboxScheduler = outboxScheduler;
        this.controlScheduler = controlScheduler;
    }

    @Test
    void contextLoads() {
    }

    @Test
    void registersLowCardinalityResilienceMetricsForTheTradeBoundary() {
        Set<String> meterNames = meterRegistry.getMeters().stream()
                .filter(meter -> PaymentTradeResilience.INSTANCE_NAME.equals(meter.getId().getTag("name")))
                .map(meter -> meter.getId().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(meterNames).contains(
                "resilience4j.circuitbreaker.state",
                "resilience4j.retry.calls",
                "resilience4j.bulkhead.available.concurrent.calls");
        assertThat(meterRegistry.find("ecommerce.http.client.resilience.rejections")
                .tag("service", "payment-service")
                .tag("dependency", "trade-service")
                .counters()).hasSize(2);
    }

    @Test
    void outboxAndControlSchedulersRunWhileRefundConsumerSchedulerIsBlocked() throws Exception {
        CountDownLatch defaultStarted = new CountDownLatch(1);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        CountDownLatch isolatedTasksRan = new CountDownLatch(2);
        AtomicReference<String> outboxThread = new AtomicReference<>();
        AtomicReference<String> controlThread = new AtomicReference<>();

        defaultScheduler.execute(() -> {
            defaultStarted.countDown();
            try {
                releaseDefault.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertThat(defaultStarted.await(1, TimeUnit.SECONDS)).isTrue();
            outboxScheduler.execute(() -> {
                outboxThread.set(Thread.currentThread().getName());
                isolatedTasksRan.countDown();
            });
            controlScheduler.execute(() -> {
                controlThread.set(Thread.currentThread().getName());
                isolatedTasksRan.countDown();
            });

            assertThat(isolatedTasksRan.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(outboxThread.get()).startsWith("payment-outbox-scheduling-");
            assertThat(controlThread.get()).startsWith("payment-control-scheduling-");
            assertThat(releaseDefault.getCount()).isEqualTo(1);
        } finally {
            releaseDefault.countDown();
        }
    }
}
