package com.ecommerce.trade.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DistributedIdPropertiesTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void explicitWorkerIdTakesPriorityOverInstanceId() {
        DistributedIdProperties properties = properties("17", "trade-instance-b");

        assertThat(properties.resolvedWorkerId()).isEqualTo(17);
    }

    @Test
    void derivesAStableWorkerIdFromTheServiceInstanceId() {
        DistributedIdProperties first = properties("", "trade-instance-b");
        DistributedIdProperties second = properties("  ", "trade-instance-b");

        assertThat(first.resolvedWorkerId())
                .isEqualTo(second.resolvedWorkerId())
                .isBetween(0, 1023);
        assertThat(properties("", "local").resolvedWorkerId()).isZero();
    }

    @Test
    void rejectsMalformedOrOutOfRangeWorkerIds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties("worker-a", "trade-instance-a").resolvedWorkerId())
                .withMessageContaining("must be an integer");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties("-1", "trade-instance-a").resolvedWorkerId())
                .withMessageContaining("between 0 and 1023");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties("1024", "trade-instance-a").resolvedWorkerId())
                .withMessageContaining("between 0 and 1023");
    }

    @Test
    void rejectsUnsafeLeaseTimingAndOversizedNamespaces() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DistributedIdProperties(
                        true, "0", "trade-service",
                        Duration.ofSeconds(30), Duration.ofSeconds(16), EPOCH, "trade-instance-a"))
                .withMessageContaining("renewal interval");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DistributedIdProperties(
                        true, "0", "x".repeat(65),
                        Duration.ofSeconds(30), Duration.ofSeconds(10), EPOCH, "trade-instance-a"))
                .withMessageContaining("at most 64 characters");
    }

    private DistributedIdProperties properties(String workerId, String instanceId) {
        return new DistributedIdProperties(
                true,
                workerId,
                "trade-service",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                EPOCH,
                instanceId);
    }
}
