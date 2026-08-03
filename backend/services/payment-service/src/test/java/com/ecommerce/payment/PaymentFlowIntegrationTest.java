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
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@AutoConfigureObservability
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
    private final ObjectMapper objectMapper;
    private final MessagingTracing messagingTracing;
    private final Tracer tracer;

    @Autowired
    PaymentFlowIntegrationTest(
            PaymentService paymentService,
            OutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            MessagingTracing messagingTracing,
            Tracer tracer) {
        this.paymentService = paymentService;
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.messagingTracing = messagingTracing;
        this.tracer = tracer;
    }

    @BeforeEach
    void stubTrade() {
        when(tradePort.getPaymentContext("ORD-1")).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TradePort.PaymentContext(
                    "ORD-1", 1L, "RSV-1", null, "PENDING_PAYMENT", new BigDecimal("39.80"),
                    Instant.now().plusSeconds(900));
        });
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM callback_security_audit");
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
    void returnsStablePaymentBeforeRemoteCallsAndScopesCustomerLookups() throws Exception {
        CreatePaymentCommand command = new CreatePaymentCommand(1L, "idem-payment-stable-001", "ORD-1", "MOCK");
        PaymentView created = paymentService.createPayment(command);
        reset(tradePort);

        PaymentView retried = paymentService.createPayment(command);

        assertThat(retried.paymentNo()).isEqualTo(created.paymentNo());
        verify(tradePort, times(0)).getPaymentContext(anyString());

        mockMvc.perform(get("/api/v1/payment/payments/by-idempotency-key/idem-payment-stable-001")
                        .with(jwt().jwt(token -> token.subject("1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentNo").value(created.paymentNo()))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mockMvc.perform(get("/api/v1/payment/payments/by-order/ORD-1")
                        .with(jwt().jwt(token -> token.subject("1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentNo").value(created.paymentNo()));

        for (String path : List.of(
                "/api/v1/payment/payments/" + created.paymentNo(),
                "/api/v1/payment/payments/by-idempotency-key/idem-payment-stable-001",
                "/api/v1/payment/payments/by-order/ORD-1")) {
            mockMvc.perform(get(path)
                            .with(jwt().jwt(token -> token.subject("2"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void doesNotCreateAPaymentWhenTheTradeFactIsUnavailable() {
        when(tradePort.getPaymentContext("ORD-UNKNOWN"))
                .thenThrow(new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE));

        assertThatThrownBy(() -> paymentService.createPayment(
                new CreatePaymentCommand(1L, "idem-payment-unavailable", "ORD-UNKNOWN", "MOCK")))
                .isInstanceOfSatisfying(PaymentException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_order", Integer.class)).isZero();
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
    void rejectsConflictingChannelTransactionIdentityAfterPaymentSettles() {
        PaymentView payment = createPayment();
        CallbackCommand settled = signed(callback(
                payment.paymentNo(), "evt-identity-001", "txn-identity-001", "SUCCESS"));
        assertThat(paymentService.processMockCallback(settled).status()).isEqualTo("SUCCESS");

        CallbackCommand sameTransaction = signed(callback(
                payment.paymentNo(), "evt-identity-002", "txn-identity-001", "SUCCESS"));
        assertThat(paymentService.processMockCallback(sameTransaction).status()).isEqualTo("SUCCESS");

        CallbackCommand conflictingTransaction = signed(callback(
                payment.paymentNo(), "evt-identity-003", "txn-identity-002", "SUCCESS"));
        assertThatThrownBy(() -> paymentService.processMockCallback(conflictingTransaction))
                .isInstanceOfSatisfying(PaymentException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));

        assertThat(paymentService.getPayment(1L, payment.paymentNo()).channelTransactionNo())
                .isEqualTo("txn-identity-001");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentSucceeded'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsChannelTransactionReuseAcrossDifferentPayments() {
        PaymentView first = createPayment();
        paymentService.processMockCallback(signed(callback(
                first.paymentNo(), "evt-reuse-001", "txn-reuse-001", "SUCCESS")));

        when(tradePort.getPaymentContext("ORD-2")).thenReturn(new TradePort.PaymentContext(
                "ORD-2", 1L, "RSV-2", null, "PENDING_PAYMENT", new BigDecimal("39.80"),
                Instant.now().plusSeconds(900)));
        PaymentView second = paymentService.createPayment(
                new CreatePaymentCommand(1L, "idem-payment-reuse-002", "ORD-2", "MOCK"));
        CallbackCommand conflicting = signed(callback(
                second.paymentNo(), "evt-reuse-002", "txn-reuse-001", "SUCCESS"));

        assertThatThrownBy(() -> paymentService.processMockCallback(conflicting))
                .isInstanceOfSatisfying(PaymentException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(PaymentError.IDEMPOTENCY_CONFLICT));
        assertThat(paymentService.getPayment(1L, second.paymentNo()).status())
                .isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction", Integer.class)).isEqualTo(1);
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
                "SELECT COUNT(*) FROM payment_callback_log", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT callback_type, claimed_external_event_id, signature_valid, error_code
                FROM callback_security_audit
                """))
                .containsEntry("CALLBACK_TYPE", "PAYMENT")
                .containsEntry("CLAIMED_EXTERNAL_EVENT_ID", "evt-invalid-001")
                .containsEntry("SIGNATURE_VALID", false)
                .containsEntry("ERROR_CODE", "INVALID_SIGNATURE");

        assertThat(paymentService.processMockCallback(signed(invalid)).status())
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM payment_callback_log", String.class))
                .isEqualTo("PROCESSED");
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
                .andExpect(jsonPath("$.service").value("payment-service"));
        mockMvc.perform(get("/actuator/businessprocesses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/businessprocesses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/businessprocesses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("payment-service"));
    }

    @Test
    void persistsInboundW3cContextWithPaymentSucceededOutbox() throws Exception {
        PaymentView payment = createPayment();
        CallbackCommand callback = signed(callback(
                payment.paymentNo(), "evt-tracing-001", "txn-tracing-001", "SUCCESS"));
        String inboundTraceparent = "00-11111111111111111111111111111111-2222222222222222-01";

        mockMvc.perform(post("/api/v1/payment/callbacks/mock")
                        .header("traceparent", inboundTraceparent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "paymentNo", callback.paymentNo(),
                                "externalEventId", callback.externalEventId(),
                                "externalTransactionNo", callback.externalTransactionNo(),
                                "status", callback.status(),
                                "amount", callback.amount(),
                                "timestamp", callback.timestamp(),
                                "signature", callback.signature()))))
                .andExpect(status().isOk());

        JsonNode envelope = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'PaymentSucceeded'", String.class));
        String persistedTraceparent = envelope.path("traceContext").path("traceparent").asText();
        assertThat(persistedTraceparent).matches("00-11111111111111111111111111111111-[0-9a-f]{16}-01");
        assertThat(envelope.path("traceId").asText()).isEqualTo("11111111111111111111111111111111");
    }

    @Test
    void retriesPaymentOutboxPublicationWithoutLosingTheEvent() throws Exception {
        PaymentView payment = createPayment();
        Span parent = tracer.nextSpan().name("payment callback test parent").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            paymentService.processMockCallback(signed(callback(
                    payment.paymentNo(), "evt-outbox-001", "txn-outbox-001", "SUCCESS")));
        } finally {
            parent.end();
        }
        String persistedTraceparent = objectMapper.readTree(jdbcTemplate.queryForObject(
                        "SELECT payload FROM outbox_event WHERE event_type = 'PaymentSucceeded'", String.class))
                .path("traceContext").path("traceparent").asText();
        AtomicReference<Map<String, String>> publicationContext = new AtomicReference<>();
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .doAnswer(invocation -> {
                    publicationContext.set(messagingTracing.capture());
                    return null;
                })
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-payment-events", 2000,
                Duration.ZERO, 100, "payment-test-job", Duration.ofSeconds(30));
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxPublisherJob job = new OutboxPublisherJob(
                outboxMapper, publisher, properties, futureClock, meterRegistry,
                objectMapper, messagingTracing);

        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        assertThat(meterRegistry.get("ecommerce.outbox.publications")
                .tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isEqualTo(1);
        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PUBLISHED");
        assertThat(traceId(publicationContext.get().get("traceparent")))
                .isEqualTo(traceId(persistedTraceparent));
        assertThat(spanId(publicationContext.get().get("traceparent")))
                .isNotEqualTo(spanId(persistedTraceparent));
        assertThat(meterRegistry.get("ecommerce.outbox.publications")
                .tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isZero();
    }

    private String traceId(String traceparent) {
        return traceparent.split("-")[1];
    }

    private String spanId(String traceparent) {
        return traceparent.split("-")[2];
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
