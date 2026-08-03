package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerFailureObservabilityTest {

    @Test
    void exposesBoundedReadOnlyReportAndSharedMetrics() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        StubStore store = new StubStore(now.minusSeconds(90));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerFailureObservability observability = new ConsumerFailureObservability(
                registry, "payment-service", store, Clock.fixed(now, ZoneOffset.UTC));

        ConsumerFailureObservability.ConsumerFailureReport report = observability.failures(1000);
        observability.failureRecorded(false);
        observability.failureRecorded(true);
        observability.recovered();

        assertThat(report.service()).isEqualTo("payment-service");
        assertThat(report.retrying()).isEqualTo(2);
        assertThat(report.needsAttention()).isEqualTo(1);
        assertThat(report.recovered()).isEqualTo(4);
        assertThat(report.oldestActiveAgeSeconds()).isEqualTo(90);
        assertThat(store.lastLimit).isEqualTo(100);
        assertThat(registry.get("ecommerce.consumer.failure.active")
                .tag("status", "retrying").gauge().value()).isEqualTo(2);
        assertThat(registry.get("ecommerce.consumer.failure.oldest.age").gauge().value()).isEqualTo(90);
        assertThat(registry.get("ecommerce.consumer.failure.transitions")
                .tag("outcome", "needs_attention").counter().count()).isEqualTo(1);
    }

    private static final class StubStore implements ConsumerFailureStore {
        private final Instant oldest;
        private int lastLimit;

        private StubStore(Instant oldest) {
            this.oldest = oldest;
        }

        @Override
        public long countByStatus(String status) {
            return switch (status) {
                case "RETRYING" -> 2;
                case "NEEDS_ATTENTION" -> 1;
                case "RECOVERED" -> 4;
                default -> 0;
            };
        }

        @Override
        public Instant selectOldestActiveFailedAt() {
            return oldest;
        }

        @Override
        public List<ConsumerFailureEntry> selectRecentActive(int limit) {
            lastLimit = limit;
            return new ArrayList<>();
        }
    }
}
