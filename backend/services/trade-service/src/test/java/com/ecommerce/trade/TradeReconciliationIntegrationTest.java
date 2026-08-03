package com.ecommerce.trade;

import com.ecommerce.trade.application.service.TradeReconciliationService;
import com.ecommerce.trade.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.ReconciliationRecordMapper;
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
class TradeReconciliationIntegrationTest {

    private final TradeReconciliationService reconciliationService;
    private final ReconciliationRecordMapper reconciliationMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    TradeReconciliationIntegrationTest(
            TradeReconciliationService reconciliationService,
            ReconciliationRecordMapper reconciliationMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.reconciliationService = reconciliationService;
        this.reconciliationMapper = reconciliationMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void createHealthyOrderFacts() {
        jdbcTemplate.update("""
                INSERT INTO trade_order
                    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
                     warehouse_code, warehouse_id, status, original_amount, discount_amount,
                     total_amount, marketing_lock_no, payment_deadline, close_reason,
                     recovery_attempts, next_recovery_at, last_error, version, created_at, updated_at)
                VALUES
                    (9101, 'ORD-RECON-9101', 1, 'idem-recon-9101', ?, 'RSV-RECON-9101',
                     'PRIMARY', 1, 'PENDING_PAYMENT', 20.00, 2.00, 18.00,
                     'LOCK-RECON-9101', CURRENT_TIMESTAMP, NULL, 0, NULL, NULL, 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "a".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO order_item
                    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
                     spec_json, image_object_key, unit_price, quantity, line_amount,
                     line_no, discount_amount, payable_amount, created_at)
                VALUES
                    (9201, 9101, 101, 201, 'Product', 'SKU-201', 'SKU', '{}', NULL,
                     20.00, 1, 20.00, 1, 2.00, 18.00, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO order_price_snapshot
                    (id, order_id, marketing_lock_no, original_amount, coupon_discount,
                     red_packet_discount, subsidy_discount, discount_amount, payable_amount,
                     pricing_version, created_at)
                VALUES
                    (9301, 9101, 'LOCK-RECON-9101', 20.00, 2.00, 0.00, 0.00,
                     2.00, 18.00, 'test-v1', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO order_status_history
                    (id, order_id, from_status, to_status, command, reason,
                     operator_type, operator_id, created_at)
                VALUES
                    (9401, 9101, 'PENDING_STOCK', 'PENDING_PAYMENT', 'RESERVE_STOCK', NULL,
                     'SYSTEM', 'test', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    ('00000000-0000-0000-0000-000000009400', 'OrderCreated',
                     'TradeOrder', 'ORD-RECON-9101', 0, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('00000000-0000-0000-0000-000000009401', 'OrderAwaitingPayment',
                     'TradeOrder', 'ORD-RECON-9101', 0, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM reconciliation_record");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM after_sale_history");
        jdbcTemplate.update("DELETE FROM after_sale_item");
        jdbcTemplate.update("DELETE FROM after_sale_order");
        jdbcTemplate.update("DELETE FROM order_status_history");
        jdbcTemplate.update("DELETE FROM order_price_snapshot");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM trade_order");
    }

    @Test
    void detectsPersistsAndResolvesOrderOwnerDomainIssuesWithoutRepairingFacts() throws Exception {
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'OrderAwaitingPaymentFaultInjected'
                WHERE aggregate_id = 'ORD-RECON-9101'
                  AND event_type = 'OrderAwaitingPayment'
                """)).isEqualTo(1);
        TradeReconciliationService.ReconciliationScanResult detected = reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(1);
        assertThat(detected.opened()).isEqualTo(1);
        reconciliationService.reconcileNow();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE id = 9101", String.class))
                .isEqualTo("PENDING_PAYMENT");

        String uri = "/api/v1/trade/admin/reconciliation/issues";
        mockMvc.perform(get(uri)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(uri).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(uri).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("ORDER"))
                .andExpect(jsonPath("$.data[0].issueType").value("ORDER_STATE_EVENT_MISSING"))
                .andExpect(jsonPath("$.data[0].occurrences").value(2));
        mockMvc.perform(get("/actuator/metrics/ecommerce.reconciliation.issue.open")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'OrderAwaitingPayment'
                WHERE aggregate_id = 'ORD-RECON-9101'
                  AND event_type = 'OrderAwaitingPaymentFaultInjected'
                """)).isEqualTo(1);
        assertThat(reconciliationService.reconcileNow().resolved()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("RESOLVED", 10)).hasSize(1);
    }

    @Test
    void reportsPriceAndItemTotalsAsSeparateBoundedIssueTypes() {
        assertThat(jdbcTemplate.update(
                "UPDATE trade_order SET total_amount = 17.00 WHERE id = 9101")).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT issue_type FROM reconciliation_record
                WHERE reference_no = 'ORD-RECON-9101' ORDER BY issue_type
                """, String.class)).containsExactly(
                "ORDER_ITEM_TOTAL_MISMATCH", "ORDER_PRICE_SNAPSHOT_MISMATCH");
    }

    @Test
    void detectsMissingCreationEventAfterOrderAdvanced() {
        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'OrderCreatedFaultInjected'
                WHERE aggregate_id = 'ORD-RECON-9101' AND event_type = 'OrderCreated'
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("ORDER_STATE_EVENT_MISSING");
    }

    @Test
    void detectsMissingAppliedEventAfterAfterSaleRejected() {
        jdbcTemplate.update("""
                INSERT INTO after_sale_order
                    (id, after_sale_no, order_id, order_no, user_id, after_sale_type, status,
                     idempotency_key, request_hash, reason, review_reason, refund_amount,
                     warehouse_id, reservation_no, return_receipt_no, refund_no, version,
                     created_at, updated_at, approved_at, completed_at)
                VALUES
                    (9501, 'AS-RECON-9501', 9101, 'ORD-RECON-9101', 1, 'WHOLE_RETURN_REFUND',
                     'REJECTED', 'idem-as-recon-9501', ?, 'damaged', 'rejected', 18.00,
                     1, 'RSV-RECON-9101', NULL, NULL, 1,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)
                """, "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO after_sale_item
                    (id, after_sale_id, order_item_id, line_no, sku_id, product_title, sku_name,
                     quantity, line_amount, discount_amount, refundable_amount, created_at)
                VALUES
                    (9601, 9501, 9201, 1, 201, 'Product', 'SKU', 1,
                     20.00, 2.00, 18.00, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO after_sale_history
                    (id, after_sale_id, from_status, to_status, command, reason,
                     operator_type, operator_id, created_at)
                VALUES
                    (9701, 9501, NULL, 'APPLIED', 'APPLY_AFTER_SALE', 'damaged',
                     'CUSTOMER', '1', CURRENT_TIMESTAMP),
                    (9702, 9501, 'APPLIED', 'REJECTED', 'REJECT_AFTER_SALE', 'rejected',
                     'ADMIN', 'admin-1', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
                     created_at, updated_at)
                VALUES
                    ('00000000-0000-0000-0000-000000009501', 'AfterSaleApplied',
                     'AfterSaleOrder', 'AS-RECON-9501', 0, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('00000000-0000-0000-0000-000000009502', 'AfterSaleRejected',
                     'AfterSaleOrder', 'AS-RECON-9501', 1, '{}', 'PUBLISHED', 0,
                     CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertThat(reconciliationService.reconcileNow().findings()).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'AfterSaleAppliedFaultInjected'
                WHERE aggregate_id = 'AS-RECON-9501' AND event_type = 'AfterSaleApplied'
                """)).isEqualTo(1);

        assertThat(reconciliationService.reconcileNow().findings()).isEqualTo(1);
        assertThat(reconciliationService.listIssues("OPEN", 10).get(0).issueType())
                .isEqualTo("AFTER_SALE_STATE_EVENT_MISSING");
    }

    @Test
    void staleScanCannotResolveAFindingRefreshedByAnotherScanner() {
        Instant firstDetectedAt = Instant.parse("2026-07-25T01:00:00Z");
        ReconciliationRecordEntity candidate = reconciliationRecord(
                9901L, "ORDER", "ORDER-RACE", "ORDER_STATE_EVENT_MISSING", firstDetectedAt);
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
}
