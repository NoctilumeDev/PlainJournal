package com.ecommerce.payment;

import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.port.DomainEventPublisher;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.service.MockCallbackSignature;
import com.ecommerce.payment.application.service.PaymentService;
import com.ecommerce.payment.infrastructure.messaging.OutboxProperties;
import com.ecommerce.payment.infrastructure.messaging.OutboxPublisherJob;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class PaymentFlowIntegrationTest {

    private static final String CALLBACK_SECRET = "test-mock-callback-secret-with-at-least-32-characters";

    @MockitoBean
    private TradePort tradePort;

    private final PaymentService paymentService;
    private final OutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    PaymentFlowIntegrationTest(
            PaymentService paymentService,
            OutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.paymentService = paymentService;
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void stubTrade() {
        when(tradePort.getPaymentContext("ORD-1")).thenReturn(new TradePort.PaymentContext(
                "ORD-1", 1L, "RSV-1", "PENDING_PAYMENT", new BigDecimal("39.80"),
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
    void requiresAuthenticationForCustomerPaymentQueries() throws Exception {
        mockMvc.perform(get("/api/v1/payment/payments/PAY-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payment/payments/PAY-1")
                        .with(jwt().jwt(token -> token.subject("1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsOnePaymentForConcurrentIdempotentRequests() throws Exception {
        CreatePaymentCommand command = new CreatePaymentCommand(1L, "idem-payment-001", "ORD-1", "MOCK");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return paymentService.createPayment(command).paymentNo();
                    })).toList();
            start.countDown();
            Set<String> paymentNumbers = new HashSet<>();
            for (Future<String> future : futures) {
                paymentNumbers.add(future.get());
            }
            assertThat(paymentNumbers).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_order", Integer.class)).isEqualTo(1);
    }

    @Test
    void acceptsOneOfManyDuplicateSuccessCallbacks() throws Exception {
        PaymentView payment = createPayment();
        CallbackCommand unsigned = callback(payment.paymentNo(), "evt-success-001", "txn-success-001", "SUCCESS");
        CallbackCommand signed = signed(unsigned);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return paymentService.processMockCallback(signed).status();
                    })).toList();
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("SUCCESS");
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_callback_log", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transaction", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentSucceeded'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidSignatureAndKeepsAnAuditRecord() {
        PaymentView payment = createPayment();
        CallbackCommand invalid = callback(payment.paymentNo(), "evt-invalid-001", "txn-invalid-001", "SUCCESS");

        assertThatThrownBy(() -> paymentService.processMockCallback(invalid))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.INVALID_SIGNATURE));
        assertThat(paymentService.getPayment(1L, payment.paymentNo()).status()).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM payment_callback_log", String.class)).isEqualTo("REJECTED");
    }

    @Test
    void rejectsAValidlySignedAmountMismatchWithoutChangingPayment() {
        PaymentView payment = createPayment();
        CallbackCommand unsigned = new CallbackCommand(
                payment.paymentNo(), "evt-amount-001", "txn-amount-001", "SUCCESS",
                new BigDecimal("39.81"), Instant.now().getEpochSecond(), "0".repeat(64), "{}");

        assertThatThrownBy(() -> paymentService.processMockCallback(signed(unsigned)))
                .isInstanceOf(PaymentException.class)
                .satisfies(exception -> assertThat(((PaymentException) exception).error())
                        .isEqualTo(PaymentError.AMOUNT_MISMATCH));
        assertThat(paymentService.getPayment(1L, payment.paymentNo()).status()).isEqualTo("PROCESSING");
    }

    @Test
    void retriesPaymentOutboxPublicationWithoutLosingTheEvent() throws Exception {
        PaymentView payment = createPayment();
        paymentService.processMockCallback(signed(callback(
                payment.paymentNo(), "evt-outbox-001", "txn-outbox-001", "SUCCESS")));
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-payment-events", 2000,
                Duration.ZERO, 100);
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        OutboxPublisherJob job = new OutboxPublisherJob(outboxMapper, publisher, properties, futureClock);

        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        doNothing().when(publisher).publish(anyString(), anyString(), anyString());
        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PUBLISHED");
    }

    private PaymentView createPayment() {
        return paymentService.createPayment(new CreatePaymentCommand(1L, "idem-payment-base", "ORD-1", "MOCK"));
    }

    private CallbackCommand callback(String paymentNo, String eventId, String transactionNo, String status) {
        return new CallbackCommand(
                paymentNo, eventId, transactionNo, status, new BigDecimal("39.80"),
                Instant.now().getEpochSecond(), "0".repeat(64), "{}");
    }

    private CallbackCommand signed(CallbackCommand command) {
        String signature = MockCallbackSignature.sign(command, CALLBACK_SECRET);
        return new CallbackCommand(
                command.paymentNo(), command.externalEventId(), command.externalTransactionNo(),
                command.status(), command.amount(), command.timestamp(), signature, command.rawPayload());
    }
}
