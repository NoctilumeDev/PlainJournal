package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessProcessObservabilityTest {

    @Test
    void exposesBoundedStatesAndLowCardinalityMetrics() {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        StubStore store = new StubStore(now.minusSeconds(75));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessProcessObservability observability = new BusinessProcessObservability(
                registry, "trade-service", store, Clock.fixed(now, ZoneOffset.UTC));

        BusinessProcessObservability.BusinessProcessReport report = observability.processes(1000);

        assertThat(report.service()).isEqualTo("trade-service");
        assertThat(report.states()).singleElement().satisfies(state -> {
            assertThat(state.domain()).isEqualTo("ORDER");
            assertThat(state.status()).isEqualTo("PENDING_STOCK");
            assertThat(state.count()).isEqualTo(3);
            assertThat(state.oldestAgeSeconds()).isEqualTo(75);
        });
        assertThat(store.lastLimit).isEqualTo(100);
        assertThat(registry.get("ecommerce.business.process.active")
                .tag("domain", "order").tag("status", "pending_stock")
                .gauge().value()).isEqualTo(3);
        assertThat(registry.get("ecommerce.business.process.oldest.age")
                .tag("domain", "order").tag("status", "pending_stock")
                .gauge().value()).isEqualTo(75);
    }

    private static final class StubStore implements BusinessProcessStore {
        private final BusinessProcessDefinition definition =
                new BusinessProcessDefinition("ORDER", "PENDING_STOCK");
        private final Instant oldest;
        private int lastLimit;

        private StubStore(Instant oldest) {
            this.oldest = oldest;
        }

        @Override
        public List<BusinessProcessDefinition> definitions() {
            return List.of(definition);
        }

        @Override
        public long count(BusinessProcessDefinition definition) {
            return 3;
        }

        @Override
        public Instant oldestUpdatedAt(BusinessProcessDefinition definition) {
            return oldest;
        }

        @Override
        public List<BusinessProcessEntry> selectOldestActive(int limit) {
            lastLimit = limit;
            return List.of();
        }
    }
}
