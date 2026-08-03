package com.ecommerce.inventory;

import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationView;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.model.InventoryModels.StockPosition;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.application.port.DomainEventPublisher;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.inventory.application.service.OrderPaidHandler;
import com.ecommerce.inventory.application.service.OrderPaidHandler.OrderPaidCommand;
import com.ecommerce.inventory.infrastructure.messaging.OutboxProperties;
import com.ecommerce.inventory.infrastructure.messaging.OutboxPublisherJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ecommerce.inventory.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class InventoryFlowIntegrationTest {

    private final InventoryService inventoryService;
    private final OrderPaidHandler orderPaidHandler;
    private final OutboxEventMapper outboxMapper;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    InventoryFlowIntegrationTest(
            InventoryService inventoryService,
            OrderPaidHandler orderPaidHandler,
            OutboxEventMapper outboxMapper,
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.inventoryService = inventoryService;
        this.orderPaidHandler = orderPaidHandler;
        this.outboxMapper = outboxMapper;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanInventoryData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM stock_movement");
        jdbcTemplate.update("DELETE FROM inventory_return");
        jdbcTemplate.update("DELETE FROM inventory_reservation_item");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM stock_adjustment");
        jdbcTemplate.update("DELETE FROM inventory_balance");
        jdbcTemplate.update("DELETE FROM warehouse");
    }

    @Test
    void enforcesWarehouseRolesAtTheHttpBoundary() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("code", "NORTH", "name", "North Warehouse"));
        mockMvc.perform(post("/api/v1/inventory/admin/warehouses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/inventory/admin/warehouses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.code").value("NORTH"));
    }

    @Test
    void exposesPublicStockIdentifiersAsBrowserSafeStrings() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse("PUBLIC", "Public Warehouse");
        inventoryService.adjustStock("INIT-PUBLIC", warehouse.id(), 98001L, 5, "Initial stock");

        mockMvc.perform(get("/api/v1/inventory/stocks/{skuId}", 98001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").isString())
                .andExpect(jsonPath("$.data.skuId").value("98001"))
                .andExpect(jsonPath("$.data.available").value(5));
    }

    @Test
    void acceptsOnlyTrustedServiceIdentityOnInternalCommands() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse("INTERNAL", "Internal Warehouse");
        String bodyWithoutExpiry = objectMapper.writeValueAsString(Map.of(
                "reservationNo", "RES-INTERNAL",
                "orderNo", "ORDER-INTERNAL",
                "warehouseId", warehouse.id(),
                "items", List.of(Map.of("skuId", 99001L, "quantity", 1))));
        String body = objectMapper.writeValueAsString(Map.of(
                "reservationNo", "RES-INTERNAL",
                "orderNo", "ORDER-INTERNAL",
                "warehouseId", warehouse.id(),
                "expiresAt", Instant.now().plusSeconds(1800),
                "items", List.of(Map.of("skuId", 99001L, "quantity", 1))));

        mockMvc.perform(post("/api/v1/inventory/internal/reservations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/inventory/internal/reservations")
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token",
                                "test-payment-internal-token-with-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no = ?",
                Integer.class,
                "RES-INTERNAL")).isZero();

        mockMvc.perform(post("/api/v1/inventory/internal/reservations")
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token",
                                "test-trade-internal-token-with-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutExpiry))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/inventory/internal/reservations")
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token",
                                "test-trade-internal-token-with-at-least-32-characters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseId").isString())
                .andExpect(jsonPath("$.data.items[0].skuId").isString())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void reservesAtMostTheAvailableStockUnderOneThousandConcurrentRequests() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse("CONCURRENT", "Concurrent Warehouse");
        long skuId = 90001L;
        inventoryService.adjustStock("INIT-CONCURRENT", warehouse.id(), skuId, 100, "Initial stock");

        int requests = 1000;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(40);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, requests)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return inventoryService.reserve(reservation(
                                "RES-CONCURRENT-" + index,
                                "ORDER-CONCURRENT-" + index,
                                warehouse.id(),
                                skuId,
                                1)).status();
                    }))
                    .toList();
            start.countDown();

            long reserved = 0;
            long rejected = 0;
            for (Future<String> future : futures) {
                String status = future.get();
                if (status.equals("RESERVED")) {
                    reserved++;
                } else if (status.equals("REJECTED")) {
                    rejected++;
                }
            }
            assertThat(reserved).isEqualTo(100);
            assertThat(rejected).isEqualTo(900);
        } finally {
            executor.shutdownNow();
        }

        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(100);
        assertThat(position.reserved()).isEqualTo(100);
        assertThat(position.available()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_balance WHERE on_hand < 0 OR reserved < 0 OR reserved > on_hand",
                Integer.class)).isZero();
    }

    @Test
    void appliesOneConcurrentIdempotentReservationOnlyOnce() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse("IDEMPOTENT", "Idempotent Warehouse");
        long skuId = 90002L;
        inventoryService.adjustStock("INIT-IDEMPOTENT", warehouse.id(), skuId, 10, "Initial stock");
        ReserveInventoryCommand command = reservation(
                "RES-SAME", "ORDER-SAME", warehouse.id(), skuId, 1);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Future<ReservationView>> futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return inventoryService.reserve(command);
                    }))
                    .toList();
            start.countDown();
            for (Future<ReservationView> future : futures) {
                assertThat(future.get().status()).isEqualTo("RESERVED");
            }
        } finally {
            executor.shutdownNow();
        }

        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.reserved()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no = 'RES-SAME'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE reservation_no = 'RES-SAME' AND movement_type = 'RESERVE'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsReusingAReservationNumberForDifferentRequestData() {
        WarehouseView warehouse = inventoryService.createWarehouse("HASH", "Hash Warehouse");
        long skuId = 90003L;
        inventoryService.adjustStock("INIT-HASH", warehouse.id(), skuId, 10, "Initial stock");
        inventoryService.reserve(reservation("RES-HASH", "ORDER-HASH", warehouse.id(), skuId, 1));

        assertThatThrownBy(() -> inventoryService.reserve(
                reservation("RES-HASH", "ORDER-HASH", warehouse.id(), skuId, 2)))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(((InventoryException) exception).error())
                        .isEqualTo(InventoryError.IDEMPOTENCY_CONFLICT));

        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.reserved()).isEqualTo(1);
    }

    @Test
    void rejectsReusingAReservationNumberWithDifferentExpiry() {
        WarehouseView warehouse = inventoryService.createWarehouse("HASH_EXPIRY", "Hash Expiry Warehouse");
        long skuId = 90004L;
        inventoryService.adjustStock("INIT-HASH-EXPIRY", warehouse.id(), skuId, 10, "Initial stock");
        Instant originalExpiry = Instant.parse("2099-01-01T00:30:00Z");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-HASH-EXPIRY",
                "ORDER-HASH-EXPIRY",
                warehouse.id(),
                originalExpiry,
                List.of(new ReservationLineCommand(skuId, 1))));

        assertThatThrownBy(() -> inventoryService.reserve(new ReserveInventoryCommand(
                "RES-HASH-EXPIRY",
                "ORDER-HASH-EXPIRY",
                warehouse.id(),
                originalExpiry.plusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 1)))))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(((InventoryException) exception).error())
                        .isEqualTo(InventoryError.IDEMPOTENCY_CONFLICT));

        ReservationView stored = inventoryService.getReservation("RES-HASH-EXPIRY");
        assertThat(stored.expiresAt()).isEqualTo(originalExpiry);
        assertThat(inventoryService.getStockPosition(warehouse.id(), skuId).reserved()).isEqualTo(1);
    }

    @Test
    void rollsBackEarlierSkuReservationsWhenOneSkuIsUnavailable() {
        WarehouseView warehouse = inventoryService.createWarehouse("MULTI", "Multi SKU Warehouse");
        inventoryService.adjustStock("INIT-MULTI-1", warehouse.id(), 91001L, 5, "Initial stock");

        ReservationView result = inventoryService.reserve(new ReserveInventoryCommand(
                "RES-MULTI",
                "ORDER-MULTI",
                warehouse.id(),
                Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(91001L, 2), new ReservationLineCommand(91002L, 1))
        ));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(inventoryService.getStockPosition(warehouse.id(), 91001L).reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE reservation_no = 'RES-MULTI'",
                Integer.class)).isZero();
    }

    @Test
    void confirmsAndReleasesReservationsWithoutBreakingTheStockEquation() {
        WarehouseView warehouse = inventoryService.createWarehouse("LIFECYCLE", "Lifecycle Warehouse");
        long skuId = 92001L;
        inventoryService.adjustStock("INIT-LIFECYCLE", warehouse.id(), skuId, 10, "Initial stock");

        inventoryService.reserve(reservation("RES-CONFIRM", "ORDER-CONFIRM", warehouse.id(), skuId, 3));
        ReservationView confirmed = inventoryService.confirmReservation("RES-CONFIRM");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(inventoryService.confirmReservation("RES-CONFIRM").status()).isEqualTo("CONFIRMED");
        StockPosition afterConfirm = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(afterConfirm.onHand()).isEqualTo(7);
        assertThat(afterConfirm.reserved()).isZero();

        inventoryService.reserve(reservation("RES-RELEASE", "ORDER-RELEASE", warehouse.id(), skuId, 2));
        ReservationView released = inventoryService.releaseReservation("RES-RELEASE");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(inventoryService.releaseReservation("RES-RELEASE").status()).isEqualTo("RELEASED");
        StockPosition afterRelease = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(afterRelease.onHand()).isEqualTo(7);
        assertThat(afterRelease.reserved()).isZero();
        assertThat(afterRelease.available()).isEqualTo(7);
    }

    @Test
    void confirmsAReservationOnceForDuplicateOrderPaidEvents() {
        WarehouseView warehouse = inventoryService.createWarehouse("ORDERPAID", "Order Paid Warehouse");
        long skuId = 92003L;
        inventoryService.adjustStock("INIT-ORDER-PAID", warehouse.id(), skuId, 5, "Initial stock");
        inventoryService.reserve(reservation("RES-ORDER-PAID", "ORDER-PAID", warehouse.id(), skuId, 2));
        OrderPaidCommand event = new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000201", "ORDER-PAID", "RES-ORDER-PAID");

        orderPaidHandler.handle(event);
        orderPaidHandler.handle(event);
        assertThatThrownBy(() -> orderPaidHandler.handle(new OrderPaidCommand(
                event.eventId(), "ORDER-PAID-CONFLICT", event.reservationNo())))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(((InventoryException) exception).error())
                        .isEqualTo(InventoryError.IDEMPOTENCY_CONFLICT));

        assertThat(inventoryService.getReservation("RES-ORDER-PAID").status()).isEqualTo("CONFIRMED");
        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(3);
        assertThat(position.reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE movement_type = 'CONFIRM'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsAnImpossibleOrderPaidEventAfterInventoryAlreadyExpired() {
        WarehouseView warehouse = inventoryService.createWarehouse(
                "ORDERPAID_EXPIRED",
                "Order Paid Expired Warehouse");
        long skuId = 92006L;
        inventoryService.adjustStock(
                "INIT-ORDER-PAID-EXPIRED",
                warehouse.id(),
                skuId,
                5,
                "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-ORDER-PAID-EXPIRED",
                "ORDER-PAID-EXPIRED",
                warehouse.id(),
                Instant.now().plusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 2))));
        expireInDatabase("RES-ORDER-PAID-EXPIRED");
        inventoryService.expireReservation("RES-ORDER-PAID-EXPIRED");

        assertThatThrownBy(() -> orderPaidHandler.handle(new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000202",
                "ORDER-PAID-EXPIRED",
                "RES-ORDER-PAID-EXPIRED")))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(
                        ((InventoryException) exception).error())
                        .isEqualTo(InventoryError.INVALID_STATE));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-ORDER-PAID-EXPIRED' "
                        + "AND movement_type = 'CONFIRM'",
                Integer.class)).isZero();
    }

    @Test
    void expiresAReservationWithoutReducingOnHandStock() {
        WarehouseView warehouse = inventoryService.createWarehouse("EXPIRY", "Expiry Warehouse");
        long skuId = 92002L;
        inventoryService.adjustStock("INIT-EXPIRY", warehouse.id(), skuId, 4, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-EXPIRED",
                "ORDER-EXPIRED",
                warehouse.id(),
                Instant.now().plusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 2))));
        expireInDatabase("RES-EXPIRED");

        ReservationView expired = inventoryService.expireReservation("RES-EXPIRED");

        assertThat(expired.status()).isEqualTo("EXPIRED");
        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(4);
        assertThat(position.reserved()).isZero();
        assertThat(position.available()).isEqualTo(4);
    }

    @Test
    void rejectsAnAlreadyExpiredReservationWithoutHoldingStock() {
        WarehouseView warehouse = inventoryService.createWarehouse(
                "STALE_RESERVE",
                "Stale Reserve Warehouse");
        long skuId = 92007L;
        inventoryService.adjustStock(
                "INIT-STALE-RESERVE",
                warehouse.id(),
                skuId,
                4,
                "Initial stock");

        ReservationView rejected = inventoryService.reserve(new ReserveInventoryCommand(
                "RES-STALE-ON-ARRIVAL",
                "ORDER-STALE-ON-ARRIVAL",
                warehouse.id(),
                Instant.now().minusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 2))));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        StockPosition position = inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(4);
        assertThat(position.reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-STALE-ON-ARRIVAL'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE aggregate_id = 'RES-STALE-ON-ARRIVAL' "
                        + "AND event_type = 'InventoryReservationRejected'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void confirmationAtomicallyAdjudicatesAnOverdueReservationAsExpired() {
        WarehouseView warehouse = inventoryService.createWarehouse(
                "CONFIRM_EXPIRED",
                "Confirm Expired Warehouse");
        long skuId = 92004L;
        inventoryService.adjustStock(
                "INIT-CONFIRM-EXPIRED",
                warehouse.id(),
                skuId,
                4,
                "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-CONFIRM-EXPIRED",
                "ORDER-CONFIRM-EXPIRED",
                warehouse.id(),
                Instant.now().plusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 2))));
        expireInDatabase("RES-CONFIRM-EXPIRED");

        ReservationView result =
                inventoryService.confirmReservation("RES-CONFIRM-EXPIRED");

        assertThat(result.status()).isEqualTo("EXPIRED");
        StockPosition position =
                inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(4);
        assertThat(position.reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-CONFIRM-EXPIRED' "
                        + "AND movement_type = 'EXPIRE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-CONFIRM-EXPIRED' "
                        + "AND movement_type = 'CONFIRM'",
                Integer.class)).isZero();
    }

    @Test
    void concurrentConfirmationAndExpiryHaveOneExpiredInventoryOutcome() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse(
                "CONFIRM_EXPIRE_RACE",
                "Confirm Expire Race Warehouse");
        long skuId = 92005L;
        inventoryService.adjustStock(
                "INIT-CONFIRM-EXPIRE-RACE",
                warehouse.id(),
                skuId,
                5,
                "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-CONFIRM-EXPIRE-RACE",
                "ORDER-CONFIRM-EXPIRE-RACE",
                warehouse.id(),
                Instant.now().plusSeconds(60),
                List.of(new ReservationLineCommand(skuId, 2))));
        expireInDatabase("RES-CONFIRM-EXPIRE-RACE");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> confirmation = executor.submit(() -> {
                start.await();
                return inventoryService
                        .confirmReservation("RES-CONFIRM-EXPIRE-RACE")
                        .status();
            });
            Future<String> expiry = executor.submit(() -> {
                start.await();
                return inventoryService
                        .expireReservation("RES-CONFIRM-EXPIRE-RACE")
                        .status();
            });
            start.countDown();

            assertThat(confirmation.get(10, TimeUnit.SECONDS))
                    .isEqualTo("EXPIRED");
            assertThat(expiry.get(10, TimeUnit.SECONDS))
                    .isEqualTo("EXPIRED");
        } finally {
            executor.shutdownNow();
        }

        StockPosition position =
                inventoryService.getStockPosition(warehouse.id(), skuId);
        assertThat(position.onHand()).isEqualTo(5);
        assertThat(position.reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-CONFIRM-EXPIRE-RACE' "
                        + "AND movement_type = 'EXPIRE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement "
                        + "WHERE reservation_no = 'RES-CONFIRM-EXPIRE-RACE' "
                        + "AND movement_type = 'CONFIRM'",
                Integer.class)).isZero();
    }

    @Test
    void exposesOperationalDiagnosticsOnlyToAdministrators() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token", "wrong-metrics-token-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token",
                                "test-only-metrics-scrape-token-with-at-least-32-characters"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("ecommerce_consumer_failure_active_events")));
        mockMvc.perform(get("/actuator/consumerfailures"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/consumerfailures")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/consumerfailures")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void retainsFailedOutboxEventsAndPublishesThemOnRetry() throws Exception {
        WarehouseView warehouse = inventoryService.createWarehouse("OUTBOX", "Outbox Warehouse");
        inventoryService.adjustStock("INIT-OUTBOX", warehouse.id(), 93001L, 2, "Initial stock");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE event_type = 'StockAdjusted'",
                String.class)).isEqualTo("PENDING");

        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-inventory-events", 2000,
                Duration.ZERO, 50, 2, "inventory-test-job",
                Duration.ofSeconds(30), Duration.ofSeconds(5));
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxPublisherJob job = new OutboxPublisherJob(
                outboxMapper, publisher, properties, futureClock, meterRegistry);

        try {
            job.publishPendingEvents();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                    .isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM outbox_event", Integer.class))
                    .isEqualTo(1);
            assertThat(meterRegistry.get("ecommerce.outbox.publications")
                    .tag("outcome", "failure").counter().count()).isEqualTo(1);
            assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isEqualTo(1);

            job.publishPendingEvents();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                    .isEqualTo("PUBLISHED");
            assertThat(meterRegistry.get("ecommerce.outbox.publications")
                    .tag("outcome", "success").counter().count()).isEqualTo(1);
            assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isZero();
        } finally {
            job.close();
        }
    }

    private void expireInDatabase(String reservationNo) {
        Instant databaseNow = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP(3)",
                Instant.class);
        assertThat(jdbcTemplate.update(
                "UPDATE inventory_reservation SET expires_at = ? WHERE reservation_no = ?",
                java.sql.Timestamp.from(databaseNow.minusSeconds(1)),
                reservationNo)).isEqualTo(1);
    }

    private ReserveInventoryCommand reservation(
            String reservationNo,
            String orderNo,
            Long warehouseId,
            Long skuId,
            long quantity) {
        return new ReserveInventoryCommand(
                reservationNo,
                orderNo,
                warehouseId,
                Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(skuId, quantity))
        );
    }
}
