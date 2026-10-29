package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.inventory.application.service.OrderPaidHandler;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryCoordinator;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real coordinator, SQL leases, consumer parsing and proxied inventory transactions. */
@ActiveProfiles("test")
@SpringBootTest(properties = "ecommerce.messaging.consumer-failure-retry.enabled=false")
@Timeout(45)
class ConsumerRetryBusinessLeaseIntegrationTest {

    private static final String GROUP = OrderPaidHandler.CONSUMER_GROUP;
    private static final Duration LEASE = Duration.ofSeconds(3);
    private final ConsumerFailureMapper failures;
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final OrderPaidConsumer consumer;
    private final ObjectMapper json;
    private String eventId;
    private String reservationNo;
    private String orderNo;
    private Long warehouseId;
    private final Long skuId = 701L;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String mysql = System.getProperty("l1.mysql.url");
        if (mysql == null) {
            properties.add("spring.datasource.url", () ->
                    "jdbc:h2:mem:inventory_l1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                            + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        } else {
            if (!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pj_l1_[a-z0-9_]+\\?.*")) {
                throw new IllegalArgumentException("L1 requires an isolated loopback pj_l1_ database");
            }
            properties.add("spring.datasource.url", () -> mysql);
            properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            properties.add("spring.datasource.username", () -> System.getProperty("l1.mysql.user"));
            properties.add("spring.datasource.password", () -> System.getProperty("l1.mysql.password"));
            properties.add("spring.datasource.hikari.data-source-properties.connectionTimeZone", () -> "UTC");
            properties.add("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession", () -> true);
        }
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
    }

    @Autowired
    ConsumerRetryBusinessLeaseIntegrationTest(
            ConsumerFailureMapper failures, JdbcTemplate jdbc, InventoryService inventory,
            OrderPaidHandler handler, ObjectMapper json, OrderEventConsumerProperties properties,
            ConsumerFailureRecorder recorder) {
        this.failures = failures;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.json = json;
        assertThat(AopUtils.isAopProxy(handler)).as("real transactional handler").isTrue();
        this.consumer = new OrderPaidConsumer(properties, handler, json, recorder);
    }

    @BeforeEach
    void createOwnedFixture() throws Exception {
        System.out.println("L1_DATABASE " + jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName() + " "
                        + connection.getMetaData().getDatabaseProductVersion()
                        + " isolation=" + connection.getTransactionIsolation()));
        if (System.getProperty("l1.mysql.url") != null) {
            System.out.println("L1_DATABASE_CLOCK " + jdbc.queryForMap(
                    "SELECT CURRENT_TIMESTAMP(3) AS session_now, UTC_TIMESTAMP(3) AS utc_now, @@session.time_zone AS session_zone"));
            assertThat(count("SELECT ABS(TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), CURRENT_TIMESTAMP(3)))"))
                    .as("MySQL session clock is UTC").isZero();
        }
        eventId = UUID.randomUUID().toString();
        reservationNo = "L1-R-" + eventId;
        orderNo = "L1-O-" + eventId;
        warehouseId = inventory.createWarehouse("L1-" + eventId.substring(0, 12), "L1 test").id();
        inventory.adjustStock("L1-A-" + eventId, warehouseId, skuId, 20, "L1 fixture");
        var reservation = inventory.reserve(new ReserveInventoryCommand(
                reservationNo, orderNo, warehouseId, failures.currentTime().plusSeconds(3600),
                List.of(new ReservationLineCommand(skuId, 3))));
        assertThat(reservation.status()).isEqualTo("RESERVED");
        String payload = json.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", "OrderPaid", "payloadVersion", 1,
                "payload", Map.of("orderNo", orderNo, "reservationNo", reservationNo)));
        var now = failures.currentTime();
        assertThat(failures.insertIfAbsent(eventId, GROUP, payload, 1, "RETRYING",
                "synthetic prior consumer failure", now, now.minusSeconds(1))).isEqualTo(1);
        witness("baseline");
    }

    @AfterEach
    void cleanOnlyOwnedFixture() {
        consumer.close();
        if (warehouseId == null) {
            return;
        }
        jdbc.update("DELETE FROM consumer_failure WHERE message_id = ? AND consumer_group = ?", eventId, GROUP);
        jdbc.update("DELETE FROM consumed_event WHERE event_id = ? AND consumer_group = ?", eventId, GROUP);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (?, ?)",
                reservationNo, warehouseId + ":" + skuId);
        jdbc.update("DELETE FROM stock_movement WHERE warehouse_id = ?", warehouseId);
        jdbc.update("DELETE FROM inventory_reservation_item WHERE reservation_id IN "
                + "(SELECT id FROM inventory_reservation WHERE reservation_no = ?)", reservationNo);
        jdbc.update("DELETE FROM inventory_reservation WHERE reservation_no = ?", reservationNo);
        jdbc.update("DELETE FROM stock_adjustment WHERE warehouse_id = ?", warehouseId);
        jdbc.update("DELETE FROM inventory_balance WHERE warehouse_id = ?", warehouseId);
        jdbc.update("DELETE FROM warehouse WHERE id = ?", warehouseId);
        assertThat(count("SELECT COUNT(*) FROM warehouse WHERE id = ?", warehouseId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM consumer_failure WHERE message_id = ?", eventId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", eventId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (?, ?)",
                reservationNo, warehouseId + ":" + skuId)).isZero();
        System.out.println("L1_CLEANUP " + eventId + " PASS");
    }

    @Test
    void ordinaryRecoveryConfirmsInventoryExactlyOnce() throws Exception {
        coordinator("normal", handler(consumer::retry)).retryDueFailures();
        assertConfirmedOnce();
        assertThat(retryState()).containsEntry("status", "RECOVERED");
        witness("normal-recovered");
    }

    @Test
    void oldWorkerBeforeBusinessCannotRepeatSuccessorEffects() throws Exception {
        takeover(false, false);
    }

    @Test
    void oldWorkerAfterBusinessCommitCannotRepeatEffectsOrOverwriteRecovery() throws Exception {
        takeover(true, false);
    }

    @Test
    void releasedReservationCannotBeResurrectedByEitherWorker() throws Exception {
        takeover(false, true);
    }

    private void takeover(boolean pauseAfterCommit, boolean releaseReservation) throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldWorker = coordinator("worker-a", handler(payload -> {
                if (pauseAfterCommit) {
                    oldCalls.incrementAndGet();
                    invokeBusiness(payload, releaseReservation);
                }
                paused.countDown();
                assertThat(resume.await(25, TimeUnit.SECONDS)).as("release old worker").isTrue();
                if (!pauseAfterCommit) {
                    oldCalls.incrementAndGet();
                    invokeBusiness(payload, releaseReservation);
                }
            }));
            var oldRun = executor.submit(oldWorker::retryDueFailures);
            assertThat(paused.await(10, TimeUnit.SECONDS)).as("old worker pause reached").isTrue();
            assertThat(retryState()).containsEntry("claim_owner", "worker-a");
            witness("a-paused-" + (pauseAfterCommit ? "after-commit" : "before-business"));
            if (pauseAfterCommit) {
                assertConfirmedOnce();
            } else {
                assertThat(countConfirmMovements()).isZero();
                assertThat(inventory.getReservation(reservationNo).status()).isEqualTo("RESERVED");
            }
            if (releaseReservation) {
                assertThat(inventory.releaseReservation(reservationNo).status()).isEqualTo("RELEASED");
                witness("reservation-released");
            }
            awaitDatabaseLeaseExpiry();
            coordinator("worker-b", handler(payload -> {
                newCalls.incrementAndGet();
                assertThat(retryState()).containsEntry("claim_owner", "worker-b");
                witness("b-took-over");
                invokeBusiness(payload, releaseReservation);
            })).retryDueFailures();
            assertThat(newCalls).hasValue(1);
            Map<String, Object> successorRetry = retryState();
            Map<String, Object> successorBusiness = businessState();
            if (releaseReservation) {
                assertReleasedWithoutConfirmation();
                assertThat(successorRetry).containsEntry("status", "RETRYING").containsEntry("attempts", 2)
                        .containsEntry("last_error", "InventoryException: " + InventoryError.INVALID_STATE.message());
            } else {
                assertConfirmedOnce();
                assertThat(successorRetry).containsEntry("status", "RECOVERED");
            }
            witness("b-finished");
            resume.countDown();
            oldRun.get(10, TimeUnit.SECONDS);
            assertThat(oldCalls).hasValue(1);
            assertThat(retryState()).as("old owner cannot overwrite successor retry facts").isEqualTo(successorRetry);
            assertThat(businessState()).as("no second domain effect or terminal-state resurrection").isEqualTo(successorBusiness);
            witness("a-resumed-no-second-effect");
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("worker cleanup").isTrue();
        }
    }

    private void invokeBusiness(String payload, boolean released) throws Exception {
        try {
            consumer.retry(payload);
            assertThat(released).as("released reservation must not be accepted").isFalse();
        } catch (InventoryException exception) {
            assertThat(released).as("only the released-reservation scenario may reject").isTrue();
            assertThat(exception.error()).isEqualTo(InventoryError.INVALID_STATE);
            throw exception;
        } catch (Exception exception) {
            throw new AssertionError("Unexpected handler failure must not count as a business rejection", exception);
        }
    }

    private void awaitDatabaseLeaseExpiry() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (count("SELECT COUNT(*) FROM consumer_failure WHERE message_id = ? "
                + "AND claim_owner = 'worker-a' AND claim_until <= CURRENT_TIMESTAMP(3)", eventId) != 1) {
            assertThat(System.nanoTime()).as("natural database-clock lease expiry").isLessThan(deadline);
            Thread.sleep(25);
        }
    }

    private ConsumerFailureRetryCoordinator coordinator(String owner, ConsumerFailureRetryHandler handler) {
        return new ConsumerFailureRetryCoordinator("inventory-service", failures,
                new ConsumerFailureObservability(new SimpleMeterRegistry(), "inventory-service", failures, Clock.systemUTC()),
                3, 20, Duration.ofSeconds(15), LEASE, owner, List.of(handler));
    }

    private ConsumerFailureRetryHandler handler(PayloadAction action) {
        return new ConsumerFailureRetryHandler() {
            @Override
            public String consumerGroup() { return GROUP; }
            @Override
            public void retry(String payload) throws Exception { action.run(payload); }
        };
    }

    private void assertConfirmedOnce() {
        assertThat(inventory.getReservation(reservationNo).status()).isEqualTo("CONFIRMED");
        var stock = inventory.getStockPosition(warehouseId, skuId);
        assertThat(stock.onHand()).isEqualTo(17);
        assertThat(stock.reserved()).isZero();
        assertThat(countConfirmMovements()).isEqualTo(1);
        assertThat(countConfirmEvents()).isEqualTo(1);
        assertThat(countConsumed()).isEqualTo(1);
    }

    private void assertReleasedWithoutConfirmation() {
        assertThat(inventory.getReservation(reservationNo).status()).isEqualTo("RELEASED");
        var stock = inventory.getStockPosition(warehouseId, skuId);
        assertThat(stock.onHand()).isEqualTo(20);
        assertThat(stock.reserved()).isZero();
        assertThat(countConfirmMovements()).isZero();
        assertThat(countConfirmEvents()).isZero();
        assertThat(countConsumed()).as("failed handler rolls back event receipt").isZero();
    }

    private long countConfirmMovements() {
        return count("SELECT COUNT(*) FROM stock_movement WHERE reservation_no = ? AND movement_type = 'CONFIRM'", reservationNo);
    }

    private long countConfirmEvents() {
        return count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'InventoryReservationConfirmed'", reservationNo);
    }

    private long countConsumed() {
        return count("SELECT COUNT(*) FROM consumed_event WHERE event_id = ? AND consumer_group = ?", eventId, GROUP);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Map<String, Object> retryState() {
        return jdbc.queryForMap("SELECT status, attempts, claim_owner, claim_until, recovered_at, last_error, "
                + "last_failed_at, next_attempt_at FROM consumer_failure WHERE message_id = ? AND consumer_group = ?", eventId, GROUP);
    }

    private Map<String, Object> businessState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("stock", jdbc.queryForMap("SELECT on_hand, reserved, version FROM inventory_balance WHERE warehouse_id = ? AND sku_id = ?", warehouseId, skuId));
        state.put("reservation", jdbc.queryForMap("SELECT status, version FROM inventory_reservation WHERE reservation_no = ?", reservationNo));
        state.put("confirmMovements", countConfirmMovements());
        state.put("confirmEvents", countConfirmEvents());
        state.put("consumedReceipts", countConsumed());
        state.put("allMovements", count("SELECT COUNT(*) FROM stock_movement WHERE reservation_no = ?", reservationNo));
        state.put("allReservationEvents", count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", reservationNo));
        return state;
    }

    private void witness(String stage) throws Exception {
        System.out.println("L1_WITNESS " + json.writeValueAsString(Map.of(
                "stage", stage, "eventId", eventId, "databaseTime", failures.currentTime().toString(),
                "retry", retryState(), "business", businessState())));
    }

    @FunctionalInterface
    private interface PayloadAction {
        void run(String payload) throws Exception;
    }
}
