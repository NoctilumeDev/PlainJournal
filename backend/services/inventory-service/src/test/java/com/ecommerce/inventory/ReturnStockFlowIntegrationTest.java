package com.ecommerce.inventory;

import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedItem;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnStockView;
import com.ecommerce.inventory.application.model.InventoryModels.StockPosition;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.inventory.application.service.ReturnStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@ActiveProfiles("test")
@SpringBootTest
class ReturnStockFlowIntegrationTest {

    private final InventoryService inventoryService;
    private final ReturnStockService returnStockService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ReturnStockFlowIntegrationTest(
            InventoryService inventoryService,
            ReturnStockService returnStockService,
            JdbcTemplate jdbcTemplate) {
        this.inventoryService = inventoryService;
        this.returnStockService = returnStockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
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
    void restocksAConfirmedWholeOrderExactlyOnceForEventAndLogicalDuplicates() {
        Seed seed = confirmedReservation();
        ReturnInspectedCommand command = command(
                "00000000-0000-0000-0000-000000000501", seed.warehouseId(), 2);

        ReturnStockView first = returnStockService.stock(command);
        ReturnStockView eventDuplicate = returnStockService.stock(command);
        ReturnStockView logicalDuplicate = returnStockService.stock(command(
                "00000000-0000-0000-0000-000000000502", seed.warehouseId(), 2));

        assertThat(first.status()).isEqualTo("STOCKED");
        assertThat(eventDuplicate.stockedAt()).isCloseTo(first.stockedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(logicalDuplicate.stockedAt()).isCloseTo(first.stockedAt(), within(1, ChronoUnit.MILLIS));
        StockPosition position = inventoryService.getStockPosition(seed.warehouseId(), 501L);
        assertThat(position.onHand()).isEqualTo(5);
        assertThat(position.reserved()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_return", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movement WHERE movement_type = 'RETURN'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ReturnStocked'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsReturnQuantitiesThatDoNotMatchTheConfirmedReservation() {
        Seed seed = confirmedReservation();

        assertThatThrownBy(() -> returnStockService.stock(command(
                "00000000-0000-0000-0000-000000000503", seed.warehouseId(), 1)))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(((InventoryException) exception).error())
                        .isEqualTo(InventoryError.IDEMPOTENCY_CONFLICT));

        assertThat(inventoryService.getStockPosition(seed.warehouseId(), 501L).onHand()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_return", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isZero();
    }

    @Test
    void refusesToRestockBeforeTheOriginalReservationIsConfirmed() {
        WarehouseView warehouse = inventoryService.createWarehouse("RETURN-WAIT", "Return Waiting Warehouse");
        inventoryService.adjustStock("INIT-RETURN-WAIT", warehouse.id(), 501L, 5, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-RETURN-001", "ORDER-RETURN-001", warehouse.id(), Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(501L, 2))));

        assertThatThrownBy(() -> returnStockService.stock(command(
                "00000000-0000-0000-0000-000000000504", warehouse.id(), 2)))
                .isInstanceOf(InventoryException.class)
                .satisfies(exception -> assertThat(((InventoryException) exception).error())
                        .isEqualTo(InventoryError.INVALID_STATE));
        StockPosition position = inventoryService.getStockPosition(warehouse.id(), 501L);
        assertThat(position.onHand()).isEqualTo(5);
        assertThat(position.reserved()).isEqualTo(2);
    }

    private Seed confirmedReservation() {
        WarehouseView warehouse = inventoryService.createWarehouse("RETURN", "Return Warehouse");
        inventoryService.adjustStock("INIT-RETURN", warehouse.id(), 501L, 5, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-RETURN-001", "ORDER-RETURN-001", warehouse.id(), Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(501L, 2))));
        inventoryService.confirmReservation("RES-RETURN-001");
        assertThat(inventoryService.getStockPosition(warehouse.id(), 501L).onHand()).isEqualTo(3);
        return new Seed(warehouse.id());
    }

    private ReturnInspectedCommand command(String eventId, Long warehouseId, long quantity) {
        return new ReturnInspectedCommand(
                eventId, "RET-501", "AS-501", "ORDER-RETURN-001", 1001L,
                warehouseId, "RES-RETURN-001", List.of(new ReturnInspectedItem(1, 501L, quantity)));
    }

    private record Seed(Long warehouseId) {
    }
}
