package com.ecommerce.payment;

import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.application.port.RefundChannelPort;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.service.MockCallbackSignature;
import com.ecommerce.payment.application.service.PaymentReconciliationService;
import com.ecommerce.payment.application.service.PaymentService;
import com.ecommerce.payment.application.service.RefundService;
import com.ecommerce.payment.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class PaymentReconciliationIntegrationTest {

    private static final String CALLBACK_SECRET =
            "test-mock-callback-secret-with-at-least-32-characters";
    private static final BigDecimal AMOUNT = new BigDecimal("35.00");

    @MockitoBean
    private TradePort tradePort;

    @MockitoBean
    private RefundChannelPort refundChannel;

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final PaymentReconciliationService reconciliationService;
    private final ReconciliationRecordMapper reconciliationMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    PaymentReconciliationIntegrationTest(
            PaymentService paymentService,
            RefundService refundService,
            PaymentReconciliationService reconciliationService,
            ReconciliationRecordMapper reconciliationMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.reconciliationService = reconciliationService;
        this.reconciliationMapper = reconciliationMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void stubTrade() {
        when(tradePort.getPaymentContext("ORDER-RECON-001")).thenReturn(new TradePort.PaymentContext(
                "ORDER-RECON-001", 1001L, "RES-RECON-001", null, "PENDING_PAYMENT", AMOUNT,
                Instant.now().plusSeconds(900)));
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM callback_security_audit");
        jdbcTemplate.update("DELETE FROM reconciliation_record");
        jdbcTemplate.update("DELETE FROM refund_dispatch_retry_audit");
        jdbcTemplate.update("DELETE FROM consumer_failure");
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM refund_callback_log");
        jdbcTemplate.update("DELETE FROM refund_transaction");
        jdbcTemplate.update("DELETE FROM refund_order");
        jdbcTemplate.update("DELETE FROM payment_callback_log");
        jdbcTemplate.update("DELETE FROM payment_transaction");
        jdbcTemplate.update("DELETE FROM payment_order");
    }

    @Test
    void persistsIdempotentIssuesAndResolvesThemWithoutRepairingFinancialFacts() throws Exception {
        SuccessfulFacts facts = createSuccessfulPaymentAndRefund();

        PaymentReconciliationService.ReconciliationScanResult healthy = reconciliationService.reconcileNow();
        assertThat(healthy.findings()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_record", Integer.class)).isZero();

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'RefundSucceededFaultInjected'
                WHERE aggregate_id = ? AND event_type = 'RefundSucceeded'
                """, facts.refundNo())).isEqualTo(1);

        PaymentReconciliationService.ReconciliationScanResult detected = reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(1);
        assertThat(detected.opened()).isEqualTo(1);
        reconciliationService.reconcileNow();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_record", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurrences FROM reconciliation_record", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM refund_order WHERE refund_no = ?",
                String.class, facts.refundNo())).isEqualTo("SUCCESS");

        String issuesUri = "/api/v1/payment/admin/reconciliation/issues";
        mockMvc.perform(get(issuesUri))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(issuesUri).with(customerJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(issuesUri).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].domain").value("REFUND"))
                .andExpect(jsonPath("$.data[0].referenceNo").value(facts.refundNo()))
                .andExpect(jsonPath("$.data[0].issueType").value("REFUND_SUCCESS_EVENT_MISSING"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].occurrences").value(2));
        mockMvc.perform(get(issuesUri).param("status", "INVALID").with(adminJwt()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/actuator/metrics/ecommerce.reconciliation.issue.open").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        assertThat(jdbcTemplate.update("""
                UPDATE outbox_event SET event_type = 'RefundSucceeded'
                WHERE aggregate_id = ? AND event_type = 'RefundSucceededFaultInjected'
                """, facts.refundNo())).isEqualTo(1);
        PaymentReconciliationService.ReconciliationScanResult recovered = reconciliationService.reconcileNow();
        assertThat(recovered.findings()).isZero();
        assertThat(recovered.resolved()).isEqualTo(1);

        mockMvc.perform(get(issuesUri)
                        .param("status", "RESOLVED")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data[0].resolvedAt").isNotEmpty());
        mockMvc.perform(get("/actuator/metrics/ecommerce.reconciliation.issue.open").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(0.0));
    }

    @Test
    void detectsRefundMoneyMismatchesAsSeparateBoundedIssueTypes() {
        SuccessfulFacts facts = createSuccessfulPaymentAndRefund();
        assertThat(jdbcTemplate.update(
                "UPDATE refund_order SET amount = 34.00 WHERE refund_no = ?", facts.refundNo()))
                .isEqualTo(1);

        PaymentReconciliationService.ReconciliationScanResult detected = reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(2);
        assertThat(detected.saturated()).isFalse();
        List<String> issueTypes = jdbcTemplate.queryForList("""
                SELECT issue_type FROM reconciliation_record
                WHERE reference_no = ? AND status = 'OPEN' ORDER BY issue_type
                """, String.class, facts.refundNo());
        assertThat(issueTypes).containsExactly(
                "REFUND_SOURCE_PAYMENT_MISMATCH",
                "REFUND_SUCCESS_TRANSACTION_MISMATCH");

        assertThat(jdbcTemplate.update(
                "UPDATE refund_order SET amount = 35.00 WHERE refund_no = ?", facts.refundNo()))
                .isEqualTo(1);
        assertThat(reconciliationService.reconcileNow().resolved()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_record WHERE status = 'OPEN'", Integer.class))
                .isZero();
    }

    @Test
    void detectsMultipleSuccessfulChannelTransactionsForOneOwnerRecord() {
        SuccessfulFacts facts = createSuccessfulPaymentAndRefund();
        Long paymentId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_order WHERE payment_no = ?",
                Long.class,
                facts.paymentNo());
        Long refundId = jdbcTemplate.queryForObject(
                "SELECT id FROM refund_order WHERE refund_no = ?",
                Long.class,
                facts.refundNo());
        assertThat(jdbcTemplate.update("""
                INSERT INTO payment_transaction (
                    id, payment_id, transaction_type, channel,
                    channel_transaction_no, amount, status, created_at
                ) VALUES (?, ?, 'PAYMENT', 'MOCK', ?, ?, 'SUCCESS', CURRENT_TIMESTAMP)
                """, 9902L, paymentId, "payment-txn-reconciliation-duplicate", AMOUNT))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update("""
                INSERT INTO refund_transaction (
                    id, refund_id, channel, channel_refund_no,
                    amount, status, created_at
                ) VALUES (?, ?, 'MOCK', ?, ?, 'SUCCESS', CURRENT_TIMESTAMP)
                """, 9903L, refundId, "refund-txn-reconciliation-duplicate", AMOUNT))
                .isEqualTo(1);

        PaymentReconciliationService.ReconciliationScanResult detected =
                reconciliationService.reconcileNow();
        assertThat(detected.findings()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT issue_type FROM reconciliation_record
                WHERE status = 'OPEN' ORDER BY issue_type
                """, String.class)).containsExactly(
                "PAYMENT_SUCCESS_TRANSACTION_DUPLICATE",
                "REFUND_SUCCESS_TRANSACTION_DUPLICATE");

        assertThat(jdbcTemplate.update(
                "DELETE FROM payment_transaction WHERE id = 9902")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "DELETE FROM refund_transaction WHERE id = 9903")).isEqualTo(1);
        assertThat(reconciliationService.reconcileNow().resolved()).isEqualTo(2);
    }

    @Test
    void staleScanCannotResolveAFindingRefreshedByAnotherScanner() {
        Instant firstDetectedAt = Instant.parse("2026-07-25T01:00:00Z");
        ReconciliationRecordEntity candidate = reconciliationRecord(
                9901L, "REFUND", "REFUND-RACE", "REFUND_SUCCESS_EVENT_MISSING", firstDetectedAt);
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

    private SuccessfulFacts createSuccessfulPaymentAndRefund() {
        PaymentView payment = paymentService.createPayment(new CreatePaymentCommand(
                1001L, "idem-payment-reconciliation", "ORDER-RECON-001", "MOCK"));
        CallbackCommand paymentUnsigned = new CallbackCommand(
                payment.paymentNo(), "payment-event-reconciliation", "payment-txn-reconciliation",
                "SUCCESS", AMOUNT, Instant.now().getEpochSecond(), "0".repeat(64), "{}");
        String paymentSignature = MockCallbackSignature.sign(paymentUnsigned, CALLBACK_SECRET);
        paymentService.processMockCallback(new CallbackCommand(
                paymentUnsigned.paymentNo(), paymentUnsigned.externalEventId(),
                paymentUnsigned.externalTransactionNo(), paymentUnsigned.status(), paymentUnsigned.amount(),
                paymentUnsigned.timestamp(), paymentSignature, paymentUnsigned.rawPayload()));

        RefundView refund = refundService.createFromRefundRequested(new RefundRequestedCommand(
                "00000000-0000-0000-0000-000000000701", "AS-RECON-001",
                "ORDER-RECON-001", 1001L, AMOUNT));
        RefundCallbackCommand refundUnsigned = new RefundCallbackCommand(
                refund.refundNo(), "refund-event-reconciliation", "refund-txn-reconciliation",
                "SUCCESS", AMOUNT, Instant.now().getEpochSecond(), "0".repeat(64), "{}");
        String refundSignature = MockCallbackSignature.sign(refundUnsigned, CALLBACK_SECRET);
        refundService.processMockCallback(new RefundCallbackCommand(
                refundUnsigned.refundNo(), refundUnsigned.externalEventId(),
                refundUnsigned.externalRefundNo(), refundUnsigned.status(), refundUnsigned.amount(),
                refundUnsigned.timestamp(), refundSignature, refundUnsigned.rawPayload()));
        return new SuccessfulFacts(payment.paymentNo(), refund.refundNo());
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private record SuccessfulFacts(String paymentNo, String refundNo) {
    }
}
