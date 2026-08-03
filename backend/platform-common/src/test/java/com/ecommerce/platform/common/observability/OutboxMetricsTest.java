package com.ecommerce.platform.common.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMetricsTest {

    @Test
    void exposesBacklogAgeAndCoordinationOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong pending = new AtomicLong(3);
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        AtomicReference<Instant> oldest = new AtomicReference<>(now.minusSeconds(125));
        OutboxMetrics metrics = new OutboxMetrics(
                registry,
                "trade-service",
                pending::get,
                oldest::get,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(registry.get("ecommerce.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("ecommerce.outbox.oldest.age").gauge().value()).isEqualTo(125);

        Timer.Sample succeeded = metrics.startPublication();
        metrics.publicationSucceeded(succeeded);
        Timer.Sample failed = metrics.startPublication();
        metrics.publicationFailed(failed);
        Timer.Sample conflicted = metrics.startPublication();
        metrics.publicationStateConflict(conflicted);
        metrics.claimContended();
        metrics.staleClaimsRecovered(2);

        assertThat(registry.get("ecommerce.outbox.publications")
                .tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("ecommerce.outbox.publications")
                .tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("ecommerce.outbox.publications")
                .tag("outcome", "state_conflict").counter().count()).isEqualTo(1);
        assertThat(registry.get("ecommerce.outbox.claims")
                .tag("outcome", "contended").counter().count()).isEqualTo(1);
        assertThat(registry.get("ecommerce.outbox.claims")
                .tag("outcome", "stale_recovered").counter().count()).isEqualTo(2);
        assertThat(registry.get("ecommerce.outbox.publish.duration").timer().count()).isEqualTo(3);

        pending.set(0);
        oldest.set(null);
        assertThat(registry.get("ecommerce.outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("ecommerce.outbox.oldest.age").gauge().value()).isZero();
    }
}
