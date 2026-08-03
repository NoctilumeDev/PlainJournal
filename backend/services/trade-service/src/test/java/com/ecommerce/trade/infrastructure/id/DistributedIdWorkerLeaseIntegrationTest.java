package com.ecommerce.trade.infrastructure.id;

import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.trade.infrastructure.config.DistributedIdProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ecommerce.trade.infrastructure.sharding.UnshardedTradeShardRouter;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class DistributedIdWorkerLeaseIntegrationTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private DistributedIdWorkerLeaseStore store;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("""
                CREATE TABLE distributed_id_worker_lease (
                    namespace VARCHAR(64) NOT NULL,
                    worker_id INT NOT NULL,
                    lease_owner VARCHAR(64) NOT NULL,
                    lease_until TIMESTAMP(3) NOT NULL,
                    lease_version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(3) NOT NULL,
                    updated_at TIMESTAMP(3) NOT NULL,
                    PRIMARY KEY (namespace, worker_id)
                )
                """);
        store = new DistributedIdWorkerLeaseStore(
                jdbcTemplate, new UnshardedTradeShardRouter());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void oneOwnerCanRenewAndReleaseWhileACompetitorIsRejected() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        assertThat(store.tryAcquire("trade-service", 7, "owner-a",
                now, now.plusSeconds(30))).isTrue();
        assertThat(store.tryAcquire("trade-service", 7, "owner-b",
                now.plusSeconds(1), now.plusSeconds(31))).isFalse();
        assertThat(store.renew("trade-service", 7, "owner-a",
                now.plusSeconds(10), now.plusSeconds(40))).isTrue();
        assertThat(store.renew("trade-service", 7, "owner-b",
                now.plusSeconds(10), now.plusSeconds(40))).isFalse();
        assertThat(store.release("trade-service", 7, "owner-b")).isFalse();
        assertThat(store.release("trade-service", 7, "owner-a")).isTrue();
        assertThat(leaseCount()).isZero();
    }

    @Test
    void anExpiredLeaseCanBeTakenOverButThePreviousOwnerCannotRenewIt() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        assertThat(store.tryAcquire("trade-service", 8, "owner-a",
                now, now.plusSeconds(5))).isTrue();
        assertThat(store.tryAcquire("trade-service", 8, "owner-b",
                now.plusSeconds(4), now.plusSeconds(9))).isFalse();
        assertThat(store.tryAcquire("trade-service", 8, "owner-b",
                now.plusSeconds(6), now.plusSeconds(11))).isTrue();
        assertThat(store.renew("trade-service", 8, "owner-a",
                now.plusSeconds(7), now.plusSeconds(12))).isFalse();
        assertThat(store.release("trade-service", 8, "owner-b")).isTrue();
    }

    @Test
    void aSecondManagerWithTheSameWorkerFailsUntilTheFirstReleasesIt() {
        DistributedIdProperties properties = properties(9, Duration.ofSeconds(30), Duration.ofSeconds(10));
        MutableTicker firstTicker = new MutableTicker(1_000_000_000L);
        MutableTicker secondTicker = new MutableTicker(9_000_000_000L);
        DistributedIdWorkerLeaseManager first =
                new DistributedIdWorkerLeaseManager(store, properties, firstTicker::read);
        DistributedIdWorkerLeaseManager second =
                new DistributedIdWorkerLeaseManager(store, properties, secondTicker::read);

        try {
            first.start();
            assertThat(first.isRunning()).isTrue();
            assertThatIllegalStateException()
                    .isThrownBy(second::start)
                    .withMessageContaining("worker is already leased");

            first.stop();
            second.start();
            assertThat(second.isRunning()).isTrue();
        } finally {
            first.stop();
            second.stop();
        }

        assertThat(leaseCount()).isZero();
    }

    @Test
    void losingTheDatabaseLeaseStopsFurtherIdGeneration() throws Exception {
        DistributedIdProperties properties =
                properties(10, Duration.ofMillis(500), Duration.ofMillis(50));
        DistributedIdWorkerLeaseManager manager =
                new DistributedIdWorkerLeaseManager(store, properties);
        DistributedIdGenerator generator = new DistributedIdGenerator(
                manager.workerId(),
                properties.epoch().toEpochMilli(),
                System::currentTimeMillis,
                manager::isOwned);

        try {
            manager.start();
            generator.nextId();
            jdbcTemplate.update("""
                    DELETE FROM distributed_id_worker_lease
                    WHERE namespace = ? AND worker_id = ?
                    """, properties.namespace(), manager.workerId());

            awaitLeaseLoss(manager, Duration.ofSeconds(2));

            assertThat(manager.isRunning()).isFalse();
            assertThatIllegalStateException()
                    .isThrownBy(generator::nextId)
                    .withMessageContaining("worker lease is not active");
        } finally {
            manager.stop();
        }
    }

    @Test
    void ownershipFailsClosedBeforeTheDatabaseLeaseExpiresIfRenewalIsDelayed() {
        MutableTicker ticker = new MutableTicker(1_000_000_000L);
        DistributedIdProperties properties =
                properties(11, Duration.ofSeconds(30), Duration.ofSeconds(10));
        DistributedIdWorkerLeaseManager manager =
                new DistributedIdWorkerLeaseManager(store, properties, ticker::read);
        DistributedIdGenerator generator = new DistributedIdGenerator(
                manager.workerId(),
                properties.epoch().toEpochMilli(),
                System::currentTimeMillis,
                manager::isOwned);

        try {
            manager.start();
            assertThat(manager.isOwned()).isTrue();
            generator.nextId();

            ticker.advance(Duration.ofSeconds(20));

            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.isOwned()).isFalse();
            assertThatIllegalStateException()
                    .isThrownBy(generator::nextId)
                    .withMessageContaining("worker lease is not active");
        } finally {
            manager.stop();
        }
    }

    @Test
    void databaseClockIsTheLeaseArbitrationSource() {
        Instant before = Instant.now().minusSeconds(1);
        Instant databaseNow = store.currentTime();
        Instant after = Instant.now().plusSeconds(1);

        assertThat(databaseNow).isBetween(before, after);
    }

    private DistributedIdProperties properties(
            int workerId,
            Duration leaseDuration,
            Duration renewalInterval) {
        return new DistributedIdProperties(
                true,
                Integer.toString(workerId),
                "trade-service-test",
                leaseDuration,
                renewalInterval,
                Instant.parse("2026-01-01T00:00:00Z"),
                "trade-test-" + workerId);
    }

    private int leaseCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM distributed_id_worker_lease",
                Integer.class);
    }

    private void awaitLeaseLoss(
            DistributedIdWorkerLeaseManager manager,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (manager.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static final class MutableTicker {

        private final AtomicLong current;

        private MutableTicker(long initial) {
            current = new AtomicLong(initial);
        }

        private long read() {
            return current.get();
        }

        private void advance(Duration duration) {
            current.addAndGet(duration.toNanos());
        }
    }
}
