package com.ecommerce.fulfillment;

import com.ecommerce.fulfillment.application.service.FulfillmentReconciliationService;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class FulfillmentReconciliationIntegrationTest {

    private final FulfillmentReconciliationService reconciliationService;
    private final ReconciliationRecordMapper reconciliationMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    FulfillmentReconciliationIntegrationTest(
            FulfillmentReconciliationService reconciliationService,
            ReconciliationRecordMapper reconciliationMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.reconciliationService = reconciliationService;
        this.reconciliationMapper = reconciliationMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void createHealthyFulfillmentFacts() {
        jdbcTemplate.update("""
                INSERT INTO fulfillment_order
                    (id, fulfillment_no, order_no, user_id, status, carrier, tracking_no,
                     version, created_at, updated_at, picked_at, packed_at, shipped_at, signed_at,
                     source_address_id, recipient_name, phone, province, city, district,
                     detail_address, postal_code, province_code, city_code, district_code)
                VALUES
                    (8101, 'FUL-RECON-8101', 'ORD-RECON-8101', 1, 'CREATED', NULL, NULL,
                     0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, NULL, NULL,
                     1, 'Customer', '13800000000', 'Zhejiang', 'Hangzhou', 'Xihu',
                     'Test address', '310000', '330000', '330100', '330106')
                """);
        jdbcTemplate.update("""
                INSERT INTO fulfillment_status_history
                    (id, fulfillment_id, from_status, to_status, command, reason,
                     operator_type, operator_id, created_at)
                VALUES
                    (8201, 8101, NULL, 'CREATED', 'CREATE_FULFILLMENT', NULL,
                     'SYSTEM', 'trade-service', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    ('00000000-0000-0000-0000-000000008201', 'FulfillmentCreated',
                     'FulfillmentOrder', 'FUL-RECON-8101', 0, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM reconciliation_record");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM return_status_history");
        jdbcTemplate.update("DELETE FROM return_item");
        jdbcTemplate.update("DELETE FROM return_receipt");
        jdbcTemplate.update("DELETE FROM shipment_latest_position");
        jdbcTemplate.update("DELETE FROM logistics_trace");
        jdbcTemplate.update("DELETE FROM fulfillment_exception_resolution");
        jdbcTemplate.update("DELETE FROM fulfillment_status_history");
        jdbcTemplate.update("DELETE FROM fulfillment_order");
    }

    @Test
    void detectsPersistsAndResolvesFulfillmentIssuesWithAdminOnlyReadAccess() throws Exception {
        assertThat(reconciliationService.reconcileNow().findings()).isZero();
        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'FulfillmentCreatedFaultInjected'
                WHERE aggregate_id = 'FUL-RECON-8101'
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().opened()).isEqualTo(1);
        reconciliationService.reconcileNow();
        String uri = "/api/v1/fulfillment/admin/reconciliation/issues";
        mockMvc.perform(get(uri)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(uri).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(uri).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("FULFILLMENT"))
                .andExpect(jsonPath("$.data[0].issueType").value("FULFILLMENT_STATE_EVENT_MISSING"))
                .andExpect(jsonPath("$.data[0].occurrences").value(2));
        mockMvc.perform(get("/actuator/metrics/ecommerce.reconciliation.issue.open")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'FulfillmentCreated'
                WHERE aggregate_id = 'FUL-RECON-8101'
                """)).isEqualTo(1);
        assertThat(reconciliationService.reconcileNow().resolved()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("RESOLVED", 10)).hasSize(1);
    }

    @Test
    void detectsReturnItemTotalMismatchWithoutChangingTheReceipt() {
        jdbcTemplate.update("""
                INSERT INTO return_receipt
                    (id, return_receipt_no, after_sale_no, order_no, user_id, warehouse_id,
                     reservation_no, status, refund_amount, carrier, tracking_no,
                     inspection_remark, version, created_at, updated_at, shipped_at,
                     received_at, inspected_at)
                VALUES
                    (8301, 'RET-RECON-8301', 'AS-8301', 'ORD-8301', 1, 1,
                     'RSV-8301', 'WAIT_SHIPMENT', 20.00, NULL, NULL, NULL, 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL, NULL)
                """);
        jdbcTemplate.update("""
                INSERT INTO return_item
                    (id, return_receipt_id, line_no, sku_id, quantity, refundable_amount, created_at)
                VALUES (8401, 8301, 1, 201, 1, 19.00, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO return_status_history
                    (id, return_receipt_id, from_status, to_status, command, reason,
                     operator_type, operator_id, created_at)
                VALUES
                    (8501, 8301, NULL, 'WAIT_SHIPMENT', 'CREATE_RETURN_RECEIPT', NULL,
                     'SYSTEM', 'trade-service', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    ('00000000-0000-0000-0000-000000008501', 'ReturnReceiptCreated',
                     'ReturnReceipt', 'RET-RECON-8301', 0, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("RETURN_ITEM_TOTAL_MISMATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refund_amount FROM return_receipt WHERE id = 8301", java.math.BigDecimal.class))
                .isEqualByComparingTo("20.00");
    }

    @Test
    void detectsMissingDispatchEventAfterShipmentEnteredExceptionState() {
        createShippedExceptionFacts();
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'ShipmentDispatchedFaultInjected'
                WHERE aggregate_id = 'FUL-RECON-8101' AND event_type = 'ShipmentDispatched'
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("FULFILLMENT_STATE_EVENT_MISSING");
    }

    @Test
    void detectsMissingShipmentTimestampAfterShipmentEnteredExceptionState() {
        createShippedExceptionFacts();
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE fulfillment_order SET shipped_at = NULL
                WHERE id = 8101
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("FULFILLMENT_TIMESTAMP_MISMATCH");
    }

    @Test
    void staleScanCannotResolveAFindingRefreshedByAnotherScanner() {
        Instant firstDetectedAt = Instant.parse("2026-07-25T01:00:00Z");
        ReconciliationRecordEntity candidate = reconciliationRecord(
                9901L, "RETURN", "RETURN-RACE", "RETURN_STATE_EVENT_MISSING", firstDetectedAt);
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

    private void createShippedExceptionFacts() {
        jdbcTemplate.update("""
                UPDATE fulfillment_order
                SET status = 'EXCEPTION', carrier = 'SF', tracking_no = 'SF-RECON-8101',
                    picked_at = CURRENT_TIMESTAMP, packed_at = CURRENT_TIMESTAMP,
                    shipped_at = CURRENT_TIMESTAMP, version = 4, updated_at = CURRENT_TIMESTAMP
                WHERE id = 8101
                """);
        jdbcTemplate.update("""
                INSERT INTO fulfillment_status_history
                    (id, fulfillment_id, from_status, to_status, command, reason,
                     operator_type, operator_id, created_at)
                VALUES
                    (8202, 8101, 'CREATED', 'PICKING', 'START_PICKING', NULL,
                     'WAREHOUSE', 'operator-1', CURRENT_TIMESTAMP),
                    (8203, 8101, 'PICKING', 'PACKED', 'MARK_PACKED', NULL,
                     'WAREHOUSE', 'operator-1', CURRENT_TIMESTAMP),
                    (8204, 8101, 'PACKED', 'SHIPPED', 'SHIP', NULL,
                     'WAREHOUSE', 'operator-1', CURRENT_TIMESTAMP),
                    (8205, 8101, 'SHIPPED', 'EXCEPTION', 'MARK_EXCEPTION', 'carrier timeout',
                     'WAREHOUSE', 'operator-1', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    ('00000000-0000-0000-0000-000000008202', 'ShipmentDispatched',
                     'FulfillmentOrder', 'FUL-RECON-8101', 3, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('00000000-0000-0000-0000-000000008203', 'FulfillmentExceptionDetected',
                     'FulfillmentOrder', 'FUL-RECON-8101', 4, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }
}
