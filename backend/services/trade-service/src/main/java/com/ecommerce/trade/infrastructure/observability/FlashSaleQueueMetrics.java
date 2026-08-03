package com.ecommerce.trade.infrastructure.observability;

import com.ecommerce.trade.infrastructure.persistence.mapper.FlashSaleOrderRequestMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class FlashSaleQueueMetrics {

    private final FlashSaleOrderRequestMapper requestMapper;
    private final Clock clock;
    private final Counter succeeded;
    private final Counter failed;

    public FlashSaleQueueMetrics(
            MeterRegistry meterRegistry,
            FlashSaleOrderRequestMapper requestMapper,
            Clock clock) {
        this.requestMapper = requestMapper;
        this.clock = clock;
        succeeded = completedCounter(meterRegistry, "order_created");
        failed = completedCounter(meterRegistry, "failed");
        Gauge.builder("ecommerce.flash.sale.processing.pending", requestMapper,
                        FlashSaleOrderRequestMapper::countProcessing)
                .description("Accepted flash-sale requests still awaiting a terminal order result")
                .tag("service", "trade-service")
                .register(meterRegistry);
        Gauge.builder("ecommerce.flash.sale.processing.oldest.age.seconds", this,
                        FlashSaleQueueMetrics::oldestAgeSeconds)
                .description("Age of the oldest accepted flash-sale request awaiting a terminal result")
                .tag("service", "trade-service")
                .register(meterRegistry);
        Gauge.builder("ecommerce.flash.sale.processing.needs.attention", requestMapper,
                        mapper -> mapper.countByStatus("NEEDS_ATTENTION"))
                .description("Flash-sale order requests that exhausted automatic recovery")
                .tag("service", "trade-service")
                .register(meterRegistry);
    }

    public void recordCompleted(boolean successful) {
        if (successful) {
            succeeded.increment();
        } else {
            failed.increment();
        }
    }

    private double oldestAgeSeconds() {
        Instant oldest = requestMapper.selectOldestProcessingCreatedAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }

    private Counter completedCounter(MeterRegistry meterRegistry, String result) {
        return Counter.builder("ecommerce.flash.sale.processing.completed")
                .description("Terminal flash-sale order processing results")
                .tag("service", "trade-service")
                .tag("result", result)
                .register(meterRegistry);
    }
}
