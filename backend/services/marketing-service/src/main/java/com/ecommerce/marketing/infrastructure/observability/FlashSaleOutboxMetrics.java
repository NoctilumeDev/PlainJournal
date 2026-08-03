package com.ecommerce.marketing.infrastructure.observability;

import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleOutboxEventMapper;
import com.ecommerce.platform.common.observability.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class FlashSaleOutboxMetrics {

    private final OutboxMetrics delegate;

    public FlashSaleOutboxMetrics(
            MeterRegistry meterRegistry,
            FlashSaleOutboxEventMapper outboxMapper,
            Clock clock) {
        delegate = new OutboxMetrics(
                meterRegistry,
                "marketing-service",
                outboxMapper::countUnpublished,
                outboxMapper::selectOldestUnpublishedCreatedAt,
                clock);
    }

    public Timer.Sample startPublication() {
        return delegate.startPublication();
    }

    public void publicationSucceeded(Timer.Sample sample) {
        delegate.publicationSucceeded(sample);
    }

    public void publicationFailed(Timer.Sample sample) {
        delegate.publicationFailed(sample);
    }

    public void publicationStateConflict(Timer.Sample sample) {
        delegate.publicationStateConflict(sample);
    }

    public void claimContended() {
        delegate.claimContended();
    }
}
