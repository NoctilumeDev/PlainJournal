package com.ecommerce.marketing;

import com.ecommerce.marketing.infrastructure.config.MarketingSchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class MarketingServiceApplicationTest {

    private final ThreadPoolTaskScheduler defaultScheduler;
    private final ThreadPoolTaskScheduler outboxScheduler;
    private final ThreadPoolTaskScheduler controlScheduler;

    @Autowired
    MarketingServiceApplicationTest(
            @Qualifier(MarketingSchedulingConfig.DEFAULT_SCHEDULER)
            ThreadPoolTaskScheduler defaultScheduler,
            @Qualifier(MarketingSchedulingConfig.OUTBOX_SCHEDULER)
            ThreadPoolTaskScheduler outboxScheduler,
            @Qualifier(MarketingSchedulingConfig.CONTROL_SCHEDULER)
            ThreadPoolTaskScheduler controlScheduler) {
        this.defaultScheduler = defaultScheduler;
        this.outboxScheduler = outboxScheduler;
        this.controlScheduler = controlScheduler;
    }

    @Test
    void contextLoads() {
    }

    @Test
    void outboxAndControlSchedulersRunWhileBothConsumerThreadsAreBlocked() throws Exception {
        CountDownLatch defaultStarted = new CountDownLatch(2);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        CountDownLatch isolatedTasksRan = new CountDownLatch(2);
        AtomicReference<String> outboxThread = new AtomicReference<>();
        AtomicReference<String> controlThread = new AtomicReference<>();

        for (int index = 0; index < 2; index++) {
            defaultScheduler.execute(() -> {
                defaultStarted.countDown();
                try {
                    releaseDefault.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }

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
            assertThat(outboxThread.get()).startsWith("marketing-outbox-scheduling-");
            assertThat(controlThread.get()).startsWith("marketing-control-scheduling-");
            assertThat(releaseDefault.getCount()).isEqualTo(1);
        } finally {
            releaseDefault.countDown();
        }
    }
}
