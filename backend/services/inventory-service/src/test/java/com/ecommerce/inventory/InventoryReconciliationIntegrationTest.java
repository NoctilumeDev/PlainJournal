package com.ecommerce.inventory;

import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedItem;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.application.service.InventoryReconciliationService;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.inventory.application.service.ReturnStockService;
import com.ecommerce.inventory.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class InventoryReconciliationIntegrationTest {

    private final InventoryService inventoryService;
    private final ReturnStockService returnStockService;
    private final InventoryReconciliationService reconciliationService;
    private final ReconciliationRecordMapper reconciliationMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    InventoryReconciliationIntegrationTest(
            InventoryService inventoryService,
            ReturnStockService returnStockService,
            InventoryReconciliationService reconciliationService,
            ReconciliationRecordMapper reconciliationMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.inventoryService = inventoryService;
        this.returnStockService = returnStockService;
        this.reconciliationService = reconciliationService;
        this.reconciliationMapper = reconciliationMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM reconciliation_record");
        jdbcTemplate.update("DELETE FROM inventory_return");
        jdbcTemplate.update("DELETE FROM consumer_failure");
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM stock_movement");
        jdbcTemplate.update("DELETE FROM inventory_reservation_item");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM stock_adjustment");
        jdbcTemplate.update("DELETE FROM inventory_balance");
        jdbcTemplate.update("DELETE FROM warehouse");
    }

    @Test
    void detectsAndResolvesMissingReturnEventWithoutChangingStock() throws Exception {
        ReturnFacts facts = createStockedReturn();
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'ReturnStockedFaultInjected'
                WHERE aggregate_id = ? AND event_type = 'ReturnStocked'
                """, facts.afterSaleNo())).isEqualTo(1);

        InventoryReconciliationService.ReconciliationScanResult detected = reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(1);
        assertThat(detected.opened()).isEqualTo(1);
        reconciliationService.reconcileNow();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurrences FROM reconciliation_record", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT on_hand FROM inventory_balance WHERE warehouse_id = ? AND sku_id = 501
                """, Long.class, facts.warehouseId())).isEqualTo(5L);

        String issuesUri = "/api/v1/inventory/admin/reconciliation/issues";
        mockMvc.perform(get(issuesUri))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(issuesUri).with(customerJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(issuesUri).with(warehouseJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(issuesUri).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("RETURN"))
                .andExpect(jsonPath("$.data[0].referenceNo").value(facts.afterSaleNo()))
                .andExpect(jsonPath("$.data[0].issueType").value("RETURN_EVENT_MISSING"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].occurrences").value(2));
        mockMvc.perform(get(issuesUri).param("status", "INVALID").with(adminJwt()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/actuator/metrics/ecommerce.reconciliation.issue.open").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'ReturnStocked'
                WHERE aggregate_id = ? AND event_type = 'ReturnStockedFaultInjected'
                """, facts.afterSaleNo())).isEqualTo(1);
        InventoryReconciliationService.ReconciliationScanResult recovered = reconciliationService.reconcileNow();
        assertThat(recovered.findings()).isZero();
        assertThat(recovered.resolved()).isEqualTo(1);

        mockMvc.perform(get(issuesUri).param("status", "RESOLVED").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data[0].resolvedAt").isNotEmpty());
    }

    @Test
    void detectsReservedBalanceMismatchWithoutRepairingIt() {
        WarehouseView warehouse = inventoryService.createWarehouse("RECON-BAL", "Reconciliation Balance");
        inventoryService.adjustStock("INIT-RECON-BAL", warehouse.id(), 601L, 10, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-RECON-BAL", "ORDER-RECON-BAL", warehouse.id(), Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(601L, 3))));
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE inventory_balance SET reserved = 2
                WHERE warehouse_id = ? AND sku_id = 601
                """, warehouse.id())).isEqualTo(1);
        InventoryReconciliationService.ReconciliationScanResult detected = reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT issue_type FROM reconciliation_record WHERE status = 'OPEN'
                """, String.class)).isEqualTo("BALANCE_RESERVED_MISMATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reserved FROM inventory_balance WHERE warehouse_id = ? AND sku_id = 601
                """, Long.class, warehouse.id())).isEqualTo(2L);

        assertThat(jdbcTemplate.update("""
                UPDATE inventory_balance SET reserved = 3
                WHERE warehouse_id = ? AND sku_id = 601
                """, warehouse.id())).isEqualTo(1);
        assertThat(reconciliationService.reconcileNow().resolved()).isEqualTo(1);
    }

    @Test
    void detectsMissingReservedEventAfterReservationWasConfirmed() {
        WarehouseView warehouse = inventoryService.createWarehouse(
                "RECON-EVENT-HISTORY", "Reconciliation Event History");
        inventoryService.adjustStock(
                "INIT-RECON-EVENT-HISTORY", warehouse.id(), 701L, 10, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-RECON-EVENT-HISTORY", "ORDER-RECON-EVENT-HISTORY", warehouse.id(),
                Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(701L, 2))));
        inventoryService.confirmReservation("RES-RECON-EVENT-HISTORY");
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'InventoryReservedFaultInjected'
                WHERE aggregate_id = 'RES-RECON-EVENT-HISTORY'
                  AND event_type = 'InventoryReserved'
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("RESERVATION_EVENT_MISSING");
    }

    @Test
    void staleScanCannotResolveAFindingRefreshedByAnotherScanner() {
        Instant firstDetectedAt = Instant.parse("2026-07-25T01:00:00Z");
        ReconciliationRecordEntity candidate = reconciliationRecord(
                9901L, "RETURN", "RETURN-RACE", "RETURN_EVENT_MISSING", firstDetectedAt);
        assertThat(reconciliationMapper.insertIfAbsent(candidate)).isOne();
        ReconciliationRecordEntity stale = reconciliationMapper.selectByStatus("OPEN", 10).get(0);

        Instant refreshedAt = firstDetectedAt.plusMillis(1);
        assertThat(reconciliationMapper.touchOpen(
                stale.getDomain(), stale.getReferenceNo(), stale.getIssueType(), refreshedAt)).isOne();

        assertThat(reconciliationMapper.markResolved(
                stale.getId(), stale.getOccurrences(), stale.getLastDetectedAt(),
                refreshedAt.plusMillis(1))).isZero();
        ReconciliationRecordEntity current = reconciliationMapper.selectByStatus("OPEN", 10).get(0);
        assertThat(current.getOccurrences()).isEqualTo(2);
        assertThat(current.getLastDetectedAt()).isEqualTo(refreshedAt);
    }

    private ReturnFacts createStockedReturn() {
        WarehouseView warehouse = inventoryService.createWarehouse("RECON-RET", "Reconciliation Return");
        inventoryService.adjustStock("INIT-RECON-RET", warehouse.id(), 501L, 5, "Initial stock");
        inventoryService.reserve(new ReserveInventoryCommand(
                "RES-RECON-RET", "ORDER-RECON-RET", warehouse.id(), Instant.now().plusSeconds(1800),
                List.of(new ReservationLineCommand(501L, 2))));
        inventoryService.confirmReservation("RES-RECON-RET");
        returnStockService.stock(new ReturnInspectedCommand(
                UUID.randomUUID().toString(), "RET-RECEIPT-RECON", "AS-RECON-RET",
                "ORDER-RECON-RET", 1001L, warehouse.id(), "RES-RECON-RET",
                List.of(new ReturnInspectedItem(1, 501L, 2))));
        return new ReturnFacts(warehouse.id(), "AS-RECON-RET");
    }

    private ReconciliationRecordEntity reconciliationRecord(
            Long id,
            String domain,
            String referenceNo,
            String issueType,
            Instant detectedAt) {
        ReconciliationRecordEntity record = new ReconciliationRecordEntity();
        record.setId(id);
        record.setDomain(domain);
        record.setReferenceNo(referenceNo);
        record.setIssueType(issueType);
        record.setStatus("OPEN");
        record.setOccurrences(1);
        record.setFirstDetectedAt(detectedAt);
        record.setLastDetectedAt(detectedAt);
        return record;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt().jwt(token -> token.subject("1001"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor warehouseJwt() {
        return jwt().jwt(token -> token.subject("warehouse-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private record ReturnFacts(Long warehouseId, String afterSaleNo) {
    }
}
