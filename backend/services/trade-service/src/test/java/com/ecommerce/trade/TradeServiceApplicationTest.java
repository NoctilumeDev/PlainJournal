package com.ecommerce.trade;

import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.infrastructure.config.TradeSchedulingConfig;
import com.ecommerce.trade.infrastructure.observability.TradeOrderRecoveryObservability;
import com.ecommerce.trade.infrastructure.resilience.TradeMarketingPricingLockResilience;
import com.ecommerce.trade.infrastructure.resilience.TradeSynchronousBoundaryResilience;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class TradeServiceApplicationTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    TradeOrderRecoveryObservability orderRecoveryObservability;

    @Autowired
    @Qualifier(TradeSchedulingConfig.DEFAULT_SCHEDULER)
    ThreadPoolTaskScheduler defaultScheduler;

    @Autowired
    @Qualifier(TradeSchedulingConfig.ORDER_RECOVERY_SCHEDULER)
    ThreadPoolTaskScheduler orderRecoveryScheduler;

    @Autowired
    @Qualifier(TradeSchedulingConfig.OUTBOX_SCHEDULER)
    ThreadPoolTaskScheduler outboxScheduler;

    @Autowired
    @Qualifier(TradeSchedulingConfig.FLASH_SALE_SCHEDULER)
    ThreadPoolTaskScheduler flashSaleScheduler;

    @Autowired
    @Qualifier(TradeSchedulingConfig.CONSUMER_FAILURE_SCHEDULER)
    ThreadPoolTaskScheduler consumerFailureScheduler;

    @MockitoBean
    CatalogPort catalogPort;

    @MockitoBean
    InventoryPort inventoryPort;

    @Test
    void contextLoads() {
        String instance = TradeMarketingPricingLockResilience.INSTANCE_NAME;
        assertThat(
                meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", instance).meters())
                .isNotEmpty();
        assertThat(
                meterRegistry.find("resilience4j.retry.calls").tag("name", instance).meters())
                .isNotEmpty();
        assertThat(
                meterRegistry.find("resilience4j.bulkhead.available.concurrent.calls")
                        .tag("name", instance).meters())
                .isNotEmpty();
        assertThat(
                meterRegistry.find("ecommerce.http.client.resilience.rejections")
                        .tag("service", "trade-service")
                        .tag("dependency", "marketing-service")
                        .meters())
                .hasSize(2);
        for (TradeSynchronousBoundaryResilience.Boundary boundary
                : TradeSynchronousBoundaryResilience.Boundary.values()) {
            assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
                    .tag("name", boundary.instanceName()).meters()).isNotEmpty();
            assertThat(meterRegistry.find("resilience4j.retry.calls")
                    .tag("name", boundary.instanceName()).meters()).isNotEmpty();
            assertThat(meterRegistry.find("resilience4j.bulkhead.available.concurrent.calls")
                    .tag("name", boundary.instanceName()).meters()).isNotEmpty();
        }
        assertThat(meterRegistry.find("executor.active")
                .tag("name", TradeSchedulingConfig.DEFAULT_SCHEDULER)
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.active")
                .tag("name", "tradeOrderRecoveryScheduler")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.active")
                .tag("name", TradeSchedulingConfig.OUTBOX_SCHEDULER)
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.active")
                .tag("name", TradeSchedulingConfig.FLASH_SALE_SCHEDULER)
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("executor.active")
                .tag("name", TradeSchedulingConfig.CONSUMER_FAILURE_SCHEDULER)
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("ecommerce.flash.sale.processing.pending")
                .tag("service", "trade-service")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("ecommerce.flash.sale.processing.needs.attention")
                .tag("service", "trade-service")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("ecommerce.flash.sale.processing.completed")
                .tag("result", "order_created")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("ecommerce.task.scheduler.completion.age")
                .tag("service", "trade-service")
                .tag("task", TradeOrderRecoveryObservability.TASK_NAME)
                .gauge()).isNotNull();
    }

    @Test
    void orderRecoverySchedulerRunsWhileDefaultSchedulerIsBlocked() throws Exception {
        CountDownLatch defaultStarted = new CountDownLatch(1);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        CountDownLatch recoveryRan = new CountDownLatch(1);
        AtomicReference<String> recoveryThread = new AtomicReference<>();

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
            orderRecoveryScheduler.execute(() -> {
                recoveryThread.set(Thread.currentThread().getName());
                recoveryRan.countDown();
            });
            assertThat(recoveryRan.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(recoveryThread.get()).startsWith("trade-order-recovery-");
            assertThat(releaseDefault.getCount()).isEqualTo(1);
        } finally {
            releaseDefault.countDown();
        }
    }

    @Test
    void outboxSchedulerRunsWhileDefaultSchedulerIsBlocked() throws Exception {
        CountDownLatch defaultStarted = new CountDownLatch(1);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        CountDownLatch outboxRan = new CountDownLatch(1);
        AtomicReference<String> outboxThread = new AtomicReference<>();

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
                outboxRan.countDown();
            });
            assertThat(outboxRan.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(outboxThread.get()).startsWith("trade-outbox-scheduling-");
            assertThat(releaseDefault.getCount()).isEqualTo(1);
        } finally {
            releaseDefault.countDown();
        }
    }

    @Test
    void flashSaleSchedulerRunsWhileDefaultSchedulerIsBlocked() throws Exception {
        CountDownLatch defaultStarted = new CountDownLatch(1);
        CountDownLatch releaseDefault = new CountDownLatch(1);
        CountDownLatch flashSaleRan = new CountDownLatch(1);
        AtomicReference<String> flashSaleThread = new AtomicReference<>();

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
            flashSaleScheduler.execute(() -> {
                flashSaleThread.set(Thread.currentThread().getName());
                flashSaleRan.countDown();
            });
            assertThat(flashSaleRan.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(flashSaleThread.get()).startsWith("trade-flash-sale-");
            assertThat(releaseDefault.getCount()).isEqualTo(1);
        } finally {
            releaseDefault.countDown();
        }
    }

    @Test
    void consumerFailureBacklogDoesNotBlockDefaultScheduler() throws Exception {
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        CountDownLatch defaultRan = new CountDownLatch(1);
        AtomicReference<String> defaultThread = new AtomicReference<>();

        consumerFailureScheduler.execute(() -> {
            retryStarted.countDown();
            try {
                releaseRetry.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertThat(retryStarted.await(1, TimeUnit.SECONDS)).isTrue();
            defaultScheduler.execute(() -> {
                defaultThread.set(Thread.currentThread().getName());
                defaultRan.countDown();
            });
            assertThat(defaultRan.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(defaultThread.get()).startsWith("trade-scheduling-");
            assertThat(releaseRetry.getCount()).isEqualTo(1);
        } finally {
            releaseRetry.countDown();
        }
    }

    @Test
    void orderRecoveryObservabilityRecordsSuccessAndFailure() {
        double successfulBefore = meterRegistry.get("ecommerce.task.scheduler.executions")
                .tag("service", "trade-service")
                .tag("task", TradeOrderRecoveryObservability.TASK_NAME)
                .tag("result", "success")
                .counter().count();
        double failedBefore = meterRegistry.get("ecommerce.task.scheduler.executions")
                .tag("service", "trade-service")
                .tag("task", TradeOrderRecoveryObservability.TASK_NAME)
                .tag("result", "failure")
                .counter().count();

        orderRecoveryObservability.observe(() -> { });
        assertThatThrownBy(() -> orderRecoveryObservability.observe(() -> {
            throw new IllegalStateException("test failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.get("ecommerce.task.scheduler.executions")
                .tag("result", "success").counter().count()).isEqualTo(successfulBefore + 1.0d);
        assertThat(meterRegistry.get("ecommerce.task.scheduler.executions")
                .tag("result", "failure").counter().count()).isEqualTo(failedBefore + 1.0d);
        assertThat(meterRegistry.get("ecommerce.task.scheduler.running")
                .tag("task", TradeOrderRecoveryObservability.TASK_NAME).gauge().value()).isZero();
        assertThat(meterRegistry.get("ecommerce.task.scheduler.duration")
                .tag("task", TradeOrderRecoveryObservability.TASK_NAME).timer().count()).isGreaterThanOrEqualTo(2L);
    }
}
