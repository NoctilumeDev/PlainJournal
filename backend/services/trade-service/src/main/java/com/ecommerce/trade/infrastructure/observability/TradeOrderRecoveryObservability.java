package com.ecommerce.trade.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TradeOrderRecoveryObservability {

    public static final String TASK_NAME = "order_recovery";

    private final Clock clock;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong lastCompletionMillis;
    private final Counter succeeded;
    private final Counter failed;
    private final Timer duration;

    public TradeOrderRecoveryObservability(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;
        this.lastCompletionMillis = new AtomicLong(clock.millis());
        this.succeeded = executions(meterRegistry, "success");
        this.failed = executions(meterRegistry, "failure");
        this.duration = Timer.builder("ecommerce.task.scheduler.duration")
                .description("Duration of a governed scheduled task execution")
                .tag("service", "trade-service")
                .tag("task", TASK_NAME)
                .register(meterRegistry);
        Gauge.builder("ecommerce.task.scheduler.running", running, AtomicInteger::get)
                .description("Whether a governed scheduled task is currently running")
                .tag("service", "trade-service")
                .tag("task", TASK_NAME)
                .register(meterRegistry);
        Gauge.builder("ecommerce.task.scheduler.completion.age", this, TradeOrderRecoveryObservability::completionAgeSeconds)
                .description("Seconds since a governed scheduled task last completed")
                .baseUnit("seconds")
                .tag("service", "trade-service")
                .tag("task", TASK_NAME)
                .register(meterRegistry);
    }

    public void observe(Runnable action) {
        Timer.Sample sample = Timer.start();
        running.set(1);
        try {
            action.run();
            succeeded.increment();
        } catch (RuntimeException exception) {
            failed.increment();
            throw exception;
        } finally {
            lastCompletionMillis.set(clock.millis());
            running.set(0);
            sample.stop(duration);
        }
    }

    private double completionAgeSeconds() {
        return Math.max(0L, clock.millis() - lastCompletionMillis.get()) / 1000.0d;
    }

    private Counter executions(MeterRegistry meterRegistry, String result) {
        return Counter.builder("ecommerce.task.scheduler.executions")
                .description("Governed scheduled task execution outcomes")
                .tag("service", "trade-service")
                .tag("task", TASK_NAME)
                .tag("result", result)
                .register(meterRegistry);
    }
}
