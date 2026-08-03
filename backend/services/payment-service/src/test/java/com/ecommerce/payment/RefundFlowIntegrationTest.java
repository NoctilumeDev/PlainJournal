package com.ecommerce.payment;

import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentExceptionRefundCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.application.model.PaymentModels.RetryRefundDispatchCommand;
import com.ecommerce.payment.application.port.RefundChannelPort;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.service.MockCallbackSignature;
import com.ecommerce.payment.application.service.PaymentService;
import com.ecommerce.payment.application.service.RefundService;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundOrderMapper;
import com.ecommerce.payment.infrastructure.messaging.ConsumerFailureRecorder;
import com.ecommerce.payment.infrastructure.refund.RefundDispatchJob;
import com.ecommerce.payment.infrastructure.refund.RefundDispatchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@SpringBootTest
class RefundFlowIntegrationTest {

    private static final String CALLBACK_SECRET = "test-mock-callback-secret-with-at-least-32-characters";
    private static final BigDecimal AMOUNT = new BigDecimal("35.00");

    @MockitoBean
    private TradePort tradePort;

    @MockitoBean
    private RefundChannelPort refundChannel;

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final RefundOrderMapper refundMapper;
    private final Clock clock;
    private final ConsumerFailureRecorder failureRecorder;

    @Autowired
    RefundFlowIntegrationTest(
            PaymentService paymentService,
            RefundService refundService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            RefundOrderMapper refundMapper,
            Clock clock,
            ConsumerFailureRecorder failureRecorder) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.refundMapper = refundMapper;
        this.clock = clock;
        this.failureRecorder = failureRecorder;
    }

    @BeforeEach
    void stubTrade() {
        when(tradePort.getPaymentContext("ORDER-REFUND-001")).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TradePort.PaymentContext(
                    "ORDER-REFUND-001", 1001L, "RES-REFUND-001", null, "PENDING_PAYMENT", AMOUNT,
                    Instant.now().plusSeconds(900));
        });
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM callback_security_audit");
        jdbcTemplate.update("DELETE FROM consumer_failure");
        jdbcTemplate.update("DELETE FROM refund_dispatch_retry_audit");
        jdbcTemplate.update("DELETE FROM payment_exception_refund_audit");
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
    void serializesRefundOwnerIdAsStringForBrowserSafety() throws Exception {
        RefundView view = new RefundView(
                "REF-BROWSER-ID",
                "AS-BROWSER-ID",
                "ORDER-BROWSER-ID",
                "PAY-BROWSER-ID",
                9_007_199_254_740_993L,
                "MOCK",
                "PROCESSING",
                AMOUNT,
                null,
                "PENDING",
                0,
                null,
                null,
                Instant.parse("2026-07-21T00:00:00Z"),
                Instant.parse("2026-07-21T00:00:00Z"),
                null);

        assertThat(objectMapper.writeValueAsString(view))
                .contains("\"userId\":\"9007199254740993\"");
    }

    @Test
    void createsOneWholeRefundFromEventAndRejectsASecondRefundForThePayment() {
        createSuccessfulPayment();
        RefundRequestedCommand request = request(
                "00000000-0000-0000-0000-000000000601", "AS-601", AMOUNT);

        RefundView created = refundService.createFromRefundRequested(request);
        RefundView eventDuplicate = refundService.createFromRefundRequested(request);
        RefundView logicalDuplicate = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000602", "AS-601", AMOUNT));
        assertThatThrownBy(() -> refundService.createFromRefundRequested(request(
                request.eventId(), request.afterSaleNo(), AMOUNT.add(new BigDecimal("1.00")))))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));

        assertThat(created.status()).isEqualTo("PROCESSING");
        assertThat(eventDuplicate.refundNo()).isEqualTo(created.refundNo());
        assertThat(logicalDuplicate.refundNo()).isEqualTo(created.refundNo());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isEqualTo(2);

        assertThatThrownBy(() -> refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000603", "AS-SECOND", AMOUNT)))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.INVALID_STATE));
    }

    @Test
    void createsOneAuditedExceptionalPaymentRefundOnlyFromAuthoritativeTradeState() {
        PaymentView payment = createSuccessfulPayment();
        when(tradePort.getPaymentContext("ORDER-REFUND-001"))
                .thenAnswer(ignored -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new TradePort.PaymentContext(
                        "ORDER-REFUND-001",
                        1001L,
                        "RES-REFUND-001",
                        payment.paymentNo(),
                        "PAYMENT_EXCEPTION",
                        AMOUNT,
                        Instant.now().minusSeconds(1));
                });
        CreatePaymentExceptionRefundCommand command =
                new CreatePaymentExceptionRefundCommand(
                        payment.paymentNo(),
                        "exception-refund-command-001",
                        "admin-1",
                        "Verified inventory confirmation failure");

        RefundView created = refundService.createPaymentExceptionRefund(command);

        reset(tradePort);
        when(tradePort.getPaymentContext("ORDER-REFUND-001"))
                .thenThrow(new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE));
        RefundView repeated = refundService.createPaymentExceptionRefund(command);

        assertThat(created.refundNo()).isEqualTo(repeated.refundNo());
        assertThat(created.afterSaleNo()).isEqualTo("PEX-ORDER-REFUND-001");
        assertThat(created.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(created.requestStatus()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE payment_no = ?",
                Integer.class,
                payment.paymentNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT outcome, operator_id, order_no, refund_no "
                        + "FROM payment_exception_refund_audit WHERE command_id = ?",
                command.commandId()))
                .containsEntry("OUTCOME", "ACCEPTED")
                .containsEntry("OPERATOR_ID", "admin-1")
                .containsEntry("ORDER_NO", "ORDER-REFUND-001")
                .containsEntry("REFUND_NO", created.refundNo());
        verifyNoInteractions(tradePort);

        assertThatThrownBy(() -> refundService.createPaymentExceptionRefund(
                new CreatePaymentExceptionRefundCommand(
                        payment.paymentNo(),
                        command.commandId(),
                        command.operatorId(),
                        "Different reason")))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                         .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void concurrentAuthorizedExceptionalRefundCommandsCreateOneRefundAndTwoAudits()
            throws Exception {
        PaymentView payment = createSuccessfulPayment();
        when(tradePort.getPaymentContext("ORDER-REFUND-001"))
                .thenReturn(new TradePort.PaymentContext(
                        "ORDER-REFUND-001",
                        1001L,
                        "RES-REFUND-001",
                        payment.paymentNo(),
                        "PAYMENT_EXCEPTION",
                        AMOUNT,
                        Instant.now().minusSeconds(1)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefundView> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return refundService.createPaymentExceptionRefund(
                        new CreatePaymentExceptionRefundCommand(
                                payment.paymentNo(),
                                "exception-refund-concurrent-a",
                                "admin-1",
                                "Verified late payment"));
            });
            Future<RefundView> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return refundService.createPaymentExceptionRefund(
                        new CreatePaymentExceptionRefundCommand(
                                payment.paymentNo(),
                                "exception-refund-concurrent-b",
                                "admin-1",
                                "Verified late payment"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).refundNo())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).refundNo());
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE payment_no = ?",
                Integer.class,
                payment.paymentNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_exception_refund_audit "
                        + "WHERE payment_no = ? AND outcome = 'ACCEPTED'",
                Integer.class,
                payment.paymentNo())).isEqualTo(2);
    }

    @Test
    void rejectsAndAuditsExceptionalRefundWithoutAuthoritativePaymentException() {
        PaymentView payment = createSuccessfulPayment();
        CreatePaymentExceptionRefundCommand command =
                new CreatePaymentExceptionRefundCommand(
                        payment.paymentNo(),
                        "exception-refund-command-rejected-001",
                        "admin-1",
                        "Must not bypass the trade state");

        assertThatThrownBy(() -> refundService.createPaymentExceptionRefund(command))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT outcome, error_code FROM payment_exception_refund_audit "
                        + "WHERE command_id = ?",
                command.commandId()))
                .containsEntry("OUTCOME", "REJECTED")
                .containsEntry(
                        "ERROR_CODE",
                        PaymentError.PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED.code());
    }

    @Test
    void exceptionalPaymentRefundEndpointRequiresAdminRole() throws Exception {
        PaymentView payment = createSuccessfulPayment();
        when(tradePort.getPaymentContext("ORDER-REFUND-001"))
                .thenReturn(new TradePort.PaymentContext(
                        "ORDER-REFUND-001",
                        1001L,
                        "RES-REFUND-001",
                        payment.paymentNo(),
                        "PAYMENT_EXCEPTION",
                        AMOUNT,
                        Instant.now().minusSeconds(1)));
        String path = "/api/v1/payment/admin/payments/"
                + payment.paymentNo() + "/exception-refunds";
        byte[] body = objectMapper.writeValueAsBytes(
                Map.of("reason", "Admin verified inventory confirmation failure"));

        mockMvc.perform(post(path)
                        .with(customerJwt())
                        .header("Idempotency-Key", "exception-refund-security-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path)
                        .with(adminJwt())
                        .header("Idempotency-Key", "exception-refund-security-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleNo")
                        .value("PEX-ORDER-REFUND-001"));
    }

    @Test
    void rejectsUnverifiableLegacyRefundRequestedEventIdentity() {
        createSuccessfulPayment();
        RefundRequestedCommand request = request(
                "00000000-0000-0000-0000-000000000619", "AS-619", AMOUNT);
        refundService.createFromRefundRequested(request);
        jdbcTemplate.update(
                "UPDATE consumed_event SET payload_fingerprint = NULL WHERE event_id = ?",
                request.eventId());

        assertThatThrownBy(() -> refundService.createFromRefundRequested(request))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void recordsInvalidSignatureAndRecoversFromFailedRefundToSuccess() throws Exception {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000604", "AS-604", AMOUNT));

        RefundCallbackCommand invalid = refundCallback(
                refund.refundNo(), "refund-event-invalid", "channel-refund-invalid", "SUCCESS");
        assertThatThrownBy(() -> refundService.processMockCallback(invalid))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.INVALID_SIGNATURE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_callback_log", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT callback_type, claimed_external_event_id, signature_valid, error_code
                FROM callback_security_audit
                """))
                .containsEntry("CALLBACK_TYPE", "REFUND")
                .containsEntry("CLAIMED_EXTERNAL_EVENT_ID", "refund-event-invalid")
                .containsEntry("SIGNATURE_VALID", false)
                .containsEntry("ERROR_CODE", "INVALID_SIGNATURE");

        RefundCallbackCommand failed = signed(refundCallback(
                refund.refundNo(), "refund-event-failed", "channel-refund-stable", "FAILED"));
        assertThat(refundService.processMockCallback(failed).status()).isEqualTo("FAILED");

        RefundCallbackCommand success = signed(refundCallback(
                refund.refundNo(), "refund-event-success", "channel-refund-stable", "SUCCESS"));
        RefundCallbackRequest body = new RefundCallbackRequest(
                success.refundNo(), success.externalEventId(), success.externalRefundNo(), success.status(),
                success.amount(), success.timestamp(), success.signature());
        mockMvc.perform(post("/api/v1/payment/callbacks/mock/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        RefundView completed = refundService.getForUser(1001L, refund.refundNo());
        assertThat(completed.status()).isEqualTo("SUCCESS");
        assertThat(completed.channelRefundNo()).isEqualTo("channel-refund-stable");
        assertThat(completed.refundedAt()).isNotNull();
        assertThatThrownBy(() -> refundService.processMockCallback(signed(refundCallback(
                refund.refundNo(), "refund-event-conflicting-success",
                "different-channel-refund", "SUCCESS"))))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_transaction", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM refund_transaction", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefundFailed'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefundSucceeded'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void invalidSignatureCannotReserveTrustedRefundEventIdentity() {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000605", "AS-605", AMOUNT));
        RefundCallbackCommand callback = refundCallback(
                refund.refundNo(), "refund-event-recoverable",
                "channel-refund-recoverable", "SUCCESS");

        assertThatThrownBy(() -> refundService.processMockCallback(callback))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.INVALID_SIGNATURE));

        assertThat(refundService.processMockCallback(signed(callback)).status())
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM callback_security_audit", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM refund_callback_log", String.class))
                .isEqualTo("PROCESSED");
    }

    @Test
    void persistsInboundW3cContextWithRefundSucceededOutbox() throws Exception {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000618", "AS-618", AMOUNT));
        RefundCallbackCommand callback = signed(refundCallback(
                refund.refundNo(), "refund-event-tracing", "channel-refund-tracing", "SUCCESS"));
        String inboundTraceparent = "00-33333333333333333333333333333333-4444444444444444-01";

        mockMvc.perform(post("/api/v1/payment/callbacks/mock/refunds")
                        .header("traceparent", inboundTraceparent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new RefundCallbackRequest(
                                callback.refundNo(), callback.externalEventId(), callback.externalRefundNo(),
                                callback.status(), callback.amount(), callback.timestamp(), callback.signature()))))
                .andExpect(status().isOk());

        var envelope = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'RefundSucceeded'", String.class));
        assertThat(envelope.path("traceContext").path("traceparent").asText())
                .matches("00-33333333333333333333333333333333-[0-9a-f]{16}-01");
        assertThat(envelope.path("traceId").asText())
                .isEqualTo("33333333333333333333333333333333");
    }

    @Test
    void refusesARefundAmountThatDoesNotEqualTheCapturedPayment() {
        createSuccessfulPayment();

        assertThatThrownBy(() -> refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000605", "AS-605", new BigDecimal("34.99"))))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.AMOUNT_MISMATCH));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_order", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isZero();
    }

    @Test
    void retriesAChannelTransportFailureWithoutFakingRefundSuccess() {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000606", "AS-606", AMOUNT));
        doThrow(new IllegalStateException("channel timeout"))
                .doNothing()
                .when(refundChannel).requestRefund(any());
        RefundDispatchJob job = new RefundDispatchJob(
                refundMapper,
                refundChannel,
                new RefundDispatchProperties(true, 1000, 0, Duration.ZERO,
                        "refund-test-606", Duration.ofMinutes(5), 10, 3));

        job.dispatchDueRefunds();

        RefundView pending = refundService.getForUser(1001L, refund.refundNo());
        assertThat(pending.status()).isEqualTo("PROCESSING");
        assertThat(pending.requestStatus()).isEqualTo("PENDING");
        assertThat(pending.requestAttempts()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_request_error FROM refund_order", String.class)).contains("channel timeout");

        job.dispatchDueRefunds();

        RefundView sent = refundService.getForUser(1001L, refund.refundNo());
        assertThat(sent.status()).isEqualTo("PROCESSING");
        assertThat(sent.requestStatus()).isEqualTo("SENT");
        assertThat(sent.requestAttempts()).isEqualTo(2);
        assertThat(sent.requestSentAt()).isNotNull();
        verify(refundChannel, org.mockito.Mockito.times(2)).requestRefund(any());
    }

    @Test
    void fencesAnExpiredRefundDispatcherBeforeAnotherWorkerRecordsTheResult() {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000607", "AS-607", AMOUNT));
        Long refundId = jdbcTemplate.queryForObject(
                "SELECT id FROM refund_order WHERE refund_no = ?", Long.class, refund.refundNo());
        Instant claimedAt = clock.instant().plusMillis(1);
        Instant expiredAt = claimedAt.plusSeconds(5);

        assertThat(refundMapper.claimRequest(
                refundId, "refund-owner-a", 0, claimedAt, claimedAt, expiredAt))
                .isEqualTo(1);
        assertThat(refundMapper.markRequestSent(
                refundId, "refund-owner-a", expiredAt.plusMillis(1))).isZero();
        assertThat(refundMapper.resetStaleRequestClaims(
                expiredAt.plusMillis(1), expiredAt.plusMillis(1))).isEqualTo(1);

        Instant recoveredAt = expiredAt.plusSeconds(1);
        assertThat(refundMapper.claimRequest(
                refundId, "refund-owner-b", 0, recoveredAt, recoveredAt,
                recoveredAt.plusSeconds(30))).isEqualTo(1);
        assertThat(refundMapper.markRequestFailed(
                refundId,
                "refund-owner-a",
                2,
                recoveredAt.plusSeconds(30),
                "late failure",
                recoveredAt.plusSeconds(1))).isZero();
        assertThat(refundMapper.markRequestSent(
                refundId, "refund-owner-b", recoveredAt.plusSeconds(1))).isEqualTo(1);

        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT request_status, request_attempts, request_claim_owner, request_claim_until
                FROM refund_order WHERE id = ?
                """, refundId);
        assertThat(state.get("request_status")).isEqualTo("SENT");
        assertThat(((Number) state.get("request_attempts")).intValue()).isEqualTo(1);
        assertThat(state.get("request_claim_owner")).isNull();
        assertThat(state.get("request_claim_until")).isNull();
    }

    @Test
    void rejectsAStaleRefundSelectionAndUsesPersistedAttemptsForTheTerminalDecision() {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000609", "AS-609", AMOUNT));
        Long refundId = jdbcTemplate.queryForObject(
                "SELECT id FROM refund_order WHERE refund_no = ?", Long.class, refund.refundNo());
        Instant firstClaimedAt = clock.instant().plusMillis(1);
        Instant firstLeaseUntil = firstClaimedAt.plusSeconds(30);

        assertThat(refundMapper.claimRequest(
                refundId, "refund-owner-a", 0, firstClaimedAt, firstClaimedAt,
                firstLeaseUntil)).isEqualTo(1);
        Instant nextRequestAt = firstClaimedAt.plusSeconds(60);
        assertThat(refundMapper.markRequestFailed(
                refundId,
                "refund-owner-a",
                2,
                nextRequestAt,
                "first failure",
                firstClaimedAt.plusSeconds(1))).isEqualTo(1);

        assertThat(refundMapper.claimRequest(
                refundId,
                "refund-owner-b",
                0,
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(32))).isZero();
        assertThat(refundMapper.claimRequest(
                refundId,
                "refund-owner-b",
                0,
                nextRequestAt.plusSeconds(1),
                nextRequestAt.plusSeconds(1),
                nextRequestAt.plusSeconds(31))).isZero();
        assertThat(refundMapper.claimRequest(
                refundId,
                "refund-owner-b",
                1,
                nextRequestAt.plusSeconds(1),
                nextRequestAt.plusSeconds(1),
                nextRequestAt.plusSeconds(31))).isEqualTo(1);
        assertThat(refundMapper.markRequestFailed(
                refundId,
                "refund-owner-b",
                2,
                nextRequestAt.plusSeconds(60),
                "second failure",
                nextRequestAt.plusSeconds(2))).isEqualTo(1);

        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT request_status, request_attempts, next_request_at
                FROM refund_order WHERE id = ?
                """, refundId);
        assertThat(state.get("request_status")).isEqualTo("NEEDS_ATTENTION");
        assertThat(((Number) state.get("request_attempts")).intValue()).isEqualTo(2);
        assertThat(state.get("next_request_at")).isNull();
    }

    @Test
    void authorizesOnlyRecoverableManualRetriesAndKeepsAnIdempotentAuditTrail() throws Exception {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000608", "AS-608", AMOUNT));
        doThrow(new IllegalStateException("channel remains unavailable"))
                .when(refundChannel).requestRefund(any());
        RefundDispatchJob job = new RefundDispatchJob(
                refundMapper,
                refundChannel,
                new RefundDispatchProperties(true, 1000, 0, Duration.ZERO,
                        "refund-test-608", Duration.ofMinutes(5), 10, 1));
        job.dispatchDueRefunds();

        assertThat(refundService.getForUser(1001L, refund.refundNo()).requestStatus())
                .isEqualTo("NEEDS_ATTENTION");

        String path = "/api/v1/payment/admin/refunds/{refundNo}/retry-dispatch";
        String body = objectMapper.writeValueAsString(Map.of("reason", "channel connectivity restored"));
        mockMvc.perform(post(path, refund.refundNo())
                        .header("Idempotency-Key", "refund-retry-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, refund.refundNo())
                        .with(customerJwt())
                        .header("Idempotency-Key", "refund-retry-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.requestStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.requestAttempts").value(0));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_dispatch_retry_audit", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT outcome FROM refund_dispatch_retry_audit", String.class)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator_id FROM refund_dispatch_retry_audit", String.class)).isEqualTo("admin-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT before_request_status FROM refund_dispatch_retry_audit", String.class))
                .isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT before_last_error FROM refund_dispatch_retry_audit", String.class))
                .contains("channel remains unavailable");

        reset(refundChannel);
        doNothing().when(refundChannel).requestRefund(any());
        job.dispatchDueRefunds();
        assertThat(refundService.getForUser(1001L, refund.refundNo()).requestStatus()).isEqualTo("SENT");

        mockMvc.perform(post(path, refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestStatus").value("SENT"))
                .andExpect(jsonPath("$.data.requestAttempts").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_dispatch_retry_audit", Integer.class)).isEqualTo(1);

        mockMvc.perform(post(path, refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "different request"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post(path, refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-too-early-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_RETRY_NOT_ALLOWED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_dispatch_retry_audit", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT outcome FROM refund_dispatch_retry_audit
                WHERE command_id = 'refund-retry-too-early-608'
                """, String.class)).isEqualTo("REJECTED");

        mockMvc.perform(get("/api/v1/payment/admin/refunds/{refundNo}/retry-dispatch/audits",
                        refund.refundNo())
                        .with(customerJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payment/admin/refunds/{refundNo}/retry-dispatch/audits",
                        refund.refundNo())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].commandId").value("refund-retry-too-early-608"))
                .andExpect(jsonPath("$.data[0].outcome").value("REJECTED"))
                .andExpect(jsonPath("$.data[1].commandId").value("refund-retry-608"))
                .andExpect(jsonPath("$.data[1].outcome").value("ACCEPTED"));

        RefundCallbackCommand failed = signed(refundCallback(
                refund.refundNo(), "refund-event-retry-608", "channel-refund-retry-608", "FAILED"));
        assertThat(refundService.processMockCallback(failed).status()).isEqualTo("FAILED");
        mockMvc.perform(post(path, refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-after-failure-608")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "channel failure was investigated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.requestStatus").value("PENDING"));
    }

    @Test
    void serializesConcurrentManualRetryCommandsForTheSameRefund() throws Exception {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000609", "AS-609", AMOUNT));
        doThrow(new IllegalStateException("fault injection"))
                .when(refundChannel).requestRefund(any());
        new RefundDispatchJob(
                refundMapper,
                refundChannel,
                new RefundDispatchProperties(true, 1000, 0, Duration.ZERO,
                        "refund-test-609", Duration.ofMinutes(5), 10, 1)).dispatchDueRefunds();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> retryAfter(start, new RetryRefundDispatchCommand(
                    refund.refundNo(), "concurrent-retry-609-a", "admin-1", "operator A retry")));
            Future<String> second = executor.submit(() -> retryAfter(start, new RetryRefundDispatchCommand(
                    refund.refundNo(), "concurrent-retry-609-b", "admin-2", "operator B retry")));
            start.countDown();

            assertThat(Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "REFUND_RETRY_NOT_ALLOWED");
        } finally {
            executor.shutdownNow();
        }

        assertThat(refundService.getForUser(1001L, refund.refundNo()).requestStatus()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM refund_dispatch_retry_audit WHERE outcome = 'ACCEPTED'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM refund_dispatch_retry_audit WHERE outcome = 'REJECTED'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void quarantinesPoisonMessagesAtTheConfiguredDeliveryLimitAndKeepsAnAuditRecord() throws Exception {
        MessageView message = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(message.getMessageId()).thenReturn(messageId);
        when(messageId.toString()).thenReturn("poison-message-001");
        when(message.getBody()).thenReturn(ByteBuffer.wrap(
                "{\"payloadVersion\":99}".getBytes(StandardCharsets.UTF_8)));
        when(message.getDeliveryAttempt()).thenReturn(16);

        assertThat(failureRecorder.record(message, "payment-refund-requested-v1",
                new IllegalArgumentException("Unsupported payload version"))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumer_failure", String.class)).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM consumer_failure", Integer.class)).isEqualTo(16);

        mockMvc.perform(get("/actuator/consumerfailures?limit=500")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAttention").value(1))
                .andExpect(jsonPath("$.activeFailures.length()").value(1))
                .andExpect(jsonPath("$.activeFailures[0].messageId").value("poison-message-001"))
                .andExpect(jsonPath("$.activeFailures[0].consumerGroup")
                        .value("payment-refund-requested-v1"))
                .andExpect(jsonPath("$.activeFailures[0].rawPayload").doesNotExist());

        failureRecorder.markRecovered(message, "payment-refund-requested-v1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM consumer_failure", String.class)).isEqualTo("RECOVERED");
        mockMvc.perform(get("/actuator/consumerfailures")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAttention").value(0))
                .andExpect(jsonPath("$.recovered").value(1))
                .andExpect(jsonPath("$.activeFailures.length()").value(0));
    }

    @Test
    void exposesRefundLookupToItsOwnerAndRestrictsManualDispatchRetryToAdmins() throws Exception {
        createSuccessfulPayment();
        RefundView refund = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000607", "AS-607", AMOUNT));

        mockMvc.perform(get("/api/v1/payment/refunds/by-after-sale/{afterSaleNo}", "AS-607")
                        .with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundNo").value(refund.refundNo()))
                .andExpect(jsonPath("$.data.requestStatus").value("PENDING"));
        mockMvc.perform(get("/api/v1/payment/refunds/{refundNo}", refund.refundNo())
                        .with(jwt().jwt(token -> token.subject("2002"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/payment/refunds/by-after-sale/{afterSaleNo}", "AS-607")
                        .with(jwt().jwt(token -> token.subject("2002"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/payment/admin/refunds/{refundNo}/retry-dispatch", refund.refundNo())
                        .with(customerJwt())
                        .header("Idempotency-Key", "refund-retry-security-607")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "security test"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payment/admin/refunds/{refundNo}/retry-dispatch", refund.refundNo())
                        .with(adminJwt())
                        .header("Idempotency-Key", "refund-retry-security-607")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "security test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_RETRY_NOT_ALLOWED"));
        mockMvc.perform(get("/actuator/businessprocesses")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states[0].domain").value("REFUND"))
                .andExpect(jsonPath("$.states[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.states[0].count").value(1))
                .andExpect(jsonPath("$.activeProcesses[0].referenceNo").value(refund.refundNo()))
                .andExpect(jsonPath("$.activeProcesses[0].stage").value("PENDING"))
                .andExpect(jsonPath("$.activeProcesses[0].userId").doesNotExist());
    }

    private PaymentView createSuccessfulPayment() {
        PaymentView payment = paymentService.createPayment(new CreatePaymentCommand(
                1001L, "idem-payment-refund", "ORDER-REFUND-001", "MOCK"));
        CallbackCommand unsigned = new CallbackCommand(
                payment.paymentNo(), "payment-event-refund", "payment-transaction-refund", "SUCCESS",
                AMOUNT, Instant.now().getEpochSecond(), "0".repeat(64), "{}");
        String signature = MockCallbackSignature.sign(unsigned, CALLBACK_SECRET);
        return paymentService.processMockCallback(new CallbackCommand(
                unsigned.paymentNo(), unsigned.externalEventId(), unsigned.externalTransactionNo(),
                unsigned.status(), unsigned.amount(), unsigned.timestamp(), signature, unsigned.rawPayload()));
    }

    private RefundRequestedCommand request(String eventId, String afterSaleNo, BigDecimal amount) {
        return new RefundRequestedCommand(eventId, afterSaleNo, "ORDER-REFUND-001", 1001L, amount);
    }

    private RefundCallbackCommand refundCallback(
            String refundNo,
            String eventId,
            String externalRefundNo,
            String status) {
        return new RefundCallbackCommand(
                refundNo, eventId, externalRefundNo, status, AMOUNT,
                Instant.now().getEpochSecond(), "0".repeat(64), "{}");
    }

    private RefundCallbackCommand signed(RefundCallbackCommand command) {
        String signature = MockCallbackSignature.sign(command, CALLBACK_SECRET);
        return new RefundCallbackCommand(
                command.refundNo(), command.externalEventId(), command.externalRefundNo(), command.status(),
                command.amount(), command.timestamp(), signature, command.rawPayload());
    }

    private String retryAfter(CountDownLatch start, RetryRefundDispatchCommand command) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            refundService.retryDispatch(command);
            return "ACCEPTED";
        } catch (PaymentException exception) {
            return exception.error().name();
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt().jwt(token -> token.subject("1001"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private record RefundCallbackRequest(
            String refundNo,
            String externalEventId,
            String externalRefundNo,
            String status,
            BigDecimal amount,
            long timestamp,
            String signature
    ) {
    }
}
