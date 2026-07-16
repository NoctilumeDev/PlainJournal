package com.ecommerce.payment;

import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.service.MockCallbackSignature;
import com.ecommerce.payment.application.service.PaymentService;
import com.ecommerce.payment.application.service.RefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class RefundFlowIntegrationTest {

    private static final String CALLBACK_SECRET = "test-mock-callback-secret-with-at-least-32-characters";
    private static final BigDecimal AMOUNT = new BigDecimal("35.00");

    @MockitoBean
    private TradePort tradePort;

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    RefundFlowIntegrationTest(
            PaymentService paymentService,
            RefundService refundService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void stubTrade() {
        when(tradePort.getPaymentContext("ORDER-REFUND-001")).thenReturn(new TradePort.PaymentContext(
                "ORDER-REFUND-001", 1001L, "RES-REFUND-001", "PENDING_PAYMENT", AMOUNT,
                Instant.now().plusSeconds(900)));
    }

    @AfterEach
    void cleanData() {
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
    void createsOneWholeRefundFromEventAndRejectsASecondRefundForThePayment() {
        createSuccessfulPayment();
        RefundRequestedCommand request = request(
                "00000000-0000-0000-0000-000000000601", "AS-601", AMOUNT);

        RefundView created = refundService.createFromRefundRequested(request);
        RefundView eventDuplicate = refundService.createFromRefundRequested(request);
        RefundView logicalDuplicate = refundService.createFromRefundRequested(request(
                "00000000-0000-0000-0000-000000000602", "AS-601", AMOUNT));

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
                "SELECT processing_status FROM refund_callback_log", String.class)).isEqualTo("REJECTED");

        RefundCallbackCommand failed = signed(refundCallback(
                refund.refundNo(), "refund-event-failed", "channel-refund-failed", "FAILED"));
        assertThat(refundService.processMockCallback(failed).status()).isEqualTo("FAILED");

        RefundCallbackCommand success = signed(refundCallback(
                refund.refundNo(), "refund-event-success", "channel-refund-success", "SUCCESS"));
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
        assertThat(completed.channelRefundNo()).isEqualTo("channel-refund-success");
        assertThat(completed.refundedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_transaction", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefundFailed'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefundSucceeded'", Integer.class))
                .isEqualTo(1);
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
