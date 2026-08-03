package com.ecommerce.notification;

import com.ecommerce.notification.application.model.NotificationModels.DomainEvent;
import com.ecommerce.notification.application.model.NotificationModels.DeliveryRetryView;
import com.ecommerce.notification.application.model.NotificationModels.EmailDeliveryAttempt;
import com.ecommerce.notification.application.service.NotificationApplicationService;
import com.ecommerce.notification.application.service.NotificationDeliveryService;
import com.ecommerce.notification.infrastructure.persistence.NotificationRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class NotificationFlowIntegrationTest {

    private static final long CUSTOMER_ID = 1001L;
    private static final long OTHER_CUSTOMER_ID = 1002L;
    private static final long ADMIN_ID = 2001L;

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final NotificationApplicationService service;
    private final NotificationDeliveryService deliveryService;
    private final NotificationRepository repository;

    @Autowired
    NotificationFlowIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            NotificationApplicationService service,
            NotificationDeliveryService deliveryService,
            NotificationRepository repository) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.service = service;
        this.deliveryService = deliveryService;
        this.repository = repository;
    }

    @Test
    void databaseClockReturnsAnInstantThroughJdbcDriverConversion() {
        assertThat(repository.currentTime()).isNotNull();
    }

    @AfterEach
    void cleanData() {
        jdbc.update("DELETE FROM notification_delivery_retry_audit");
        jdbc.update("DELETE FROM notification_delivery");
        jdbc.update("DELETE FROM in_app_notification");
        jdbc.update("DELETE FROM notification_task");
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM consumer_failure");
        jdbc.update("DELETE FROM notification_recipient");
    }

    @Test
    void createsOneInAppAndEmailTaskForDuplicatePaymentEventAndSupportsReadFlow() throws Exception {
        mockMvc.perform(put("/api/v1/notifications/email-preference")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "Reader@Example.com",
                                "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("reader@example.com"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        DomainEvent event = paymentEvent(UUID.randomUUID().toString());
        assertThat(service.acceptDomainEvent(event, "notification-test")).isTrue();
        assertThat(service.acceptDomainEvent(event, "notification-test")).isFalse();
        DomainEvent conflict = new DomainEvent(
                event.eventId(),
                event.eventType(),
                OTHER_CUSTOMER_ID,
                event.payload());
        assertThatThrownBy(() -> service.acceptDomainEvent(conflict, "notification-test"))
                .isInstanceOf(com.ecommerce.notification.application.exception.NotificationException.class)
                .satisfies(exception -> assertThat(
                        ((com.ecommerce.notification.application.exception.NotificationException) exception)
                                .error())
                        .isEqualTo(com.ecommerce.notification.application.exception.NotificationError
                                .IDEMPOTENCY_CONFLICT));

        assertThat(count("consumed_event")).isEqualTo(1);
        assertThat(count("notification_task")).isEqualTo(1);
        assertThat(count("in_app_notification")).isEqualTo(1);
        assertThat(count("notification_delivery")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery",
                String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT destination FROM notification_delivery",
                String.class)).isEqualTo("reader@example.com");

        long notificationId = jdbc.queryForObject(
                "SELECT id FROM in_app_notification",
                Long.class);
        mockMvc.perform(get("/api/v1/notifications")
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id")
                        .value(Long.toString(notificationId)))
                .andExpect(jsonPath("$.data.items[0].templateCode").value("PAYMENT_SUCCEEDED"))
                .andExpect(jsonPath("$.data.items[0].status").value("UNREAD"));
        mockMvc.perform(get("/api/v1/notifications")
                        .with(customerJwt(OTHER_CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));

        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void concurrentDuplicateEventsConvergeToOneNotificationFact() throws Exception {
        DomainEvent event = paymentEvent(UUID.randomUUID().toString());
        List<Boolean> accepted = runConcurrently(
                8,
                () -> service.acceptDomainEvent(event, "notification-concurrent-test"));

        assertThat(accepted).containsExactlyInAnyOrder(
                true, false, false, false, false, false, false, false);
        assertThat(count("notification_task")).isEqualTo(1);
        assertThat(count("in_app_notification")).isEqualTo(1);
        assertThat(count("notification_delivery")).isZero();
    }

    @Test
    void emailFailureBecomesNeedsAttentionAndOnlyAuditedAdminRetryCanRecoverIt() throws Exception {
        service.saveEmailPreference(CUSTOMER_ID, "reader@example.com", true);
        service.acceptDomainEvent(paymentEvent(UUID.randomUUID().toString()), "notification-test");
        jdbc.update(
                "UPDATE notification_delivery SET next_attempt_at = ?",
                Timestamp.from(Instant.EPOCH));

        List<EmailDeliveryAttempt> firstClaims = deliveryService.claimDue();
        assertThat(firstClaims).hasSize(1);
        EmailDeliveryAttempt first = firstClaims.get(0);
        deliveryService.markFailed(
                first.deliveryId(),
                first.attempt(),
                new IllegalStateException("smtp unavailable"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE id = ?",
                String.class,
                first.deliveryId())).isEqualTo("RETRY");
        jdbc.update(
                "UPDATE notification_delivery SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                first.deliveryId());
        List<EmailDeliveryAttempt> secondClaims = deliveryService.claimDue();
        assertThat(secondClaims).hasSize(1);
        EmailDeliveryAttempt second = secondClaims.get(0);
        deliveryService.markFailed(
                second.deliveryId(),
                second.attempt(),
                new IllegalStateException("smtp unavailable"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE id = ?",
                String.class,
                second.deliveryId())).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM in_app_notification WHERE status = 'UNREAD'",
                Integer.class)).isEqualTo(1);

        String request = json(Map.of(
                "commandId", "retry-email-command-1",
                "reason", "SMTP has recovered after the controlled outage"));
        mockMvc.perform(post(
                                "/api/v1/notifications/admin/email-deliveries/{id}/retry",
                                second.deliveryId())
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(
                                "/api/v1/notifications/admin/email-deliveries/{id}/retry",
                                second.deliveryId())
                        .with(adminJwt(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeStatus").value("NEEDS_ATTENTION"))
                .andExpect(jsonPath("$.data.afterStatus").value("RETRY"));
        mockMvc.perform(post(
                                "/api/v1/notifications/admin/email-deliveries/{id}/retry",
                                second.deliveryId())
                        .with(adminJwt(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());
        mockMvc.perform(post(
                                "/api/v1/notifications/admin/email-deliveries/{id}/retry",
                                second.deliveryId())
                        .with(adminJwt(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "commandId", "retry-email-command-1",
                                "reason", "A different retry reason for the same command"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(post(
                                "/api/v1/notifications/admin/email-deliveries/{id}/retry",
                                second.deliveryId())
                        .with(adminJwt(ADMIN_ID + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE id = ?",
                String.class,
                second.deliveryId())).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM notification_delivery WHERE id = ?",
                Integer.class,
                second.deliveryId())).isZero();
        assertThat(count("notification_delivery_retry_audit")).isEqualTo(1);
    }

    @Test
    void expiredEmailOwnerCannotCompleteOrFailANewerDeliveryAttempt() {
        service.saveEmailPreference(CUSTOMER_ID, "fencing@example.com", true);
        service.acceptDomainEvent(paymentEvent(UUID.randomUUID().toString()), "notification-fencing-test");
        jdbc.update(
                "UPDATE notification_delivery SET next_attempt_at = ?",
                Timestamp.from(Instant.EPOCH));

        EmailDeliveryAttempt first = deliveryService.claimDue().get(0);
        jdbc.update(
                "UPDATE notification_delivery SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                first.deliveryId());
        assertThat(deliveryService.markSent(first.deliveryId(), first.attempt())).isFalse();

        EmailDeliveryAttempt second = deliveryService.claimDue().get(0);
        assertThat(second.attempt()).isEqualTo(first.attempt() + 1);
        assertThat(deliveryService.markFailed(
                first.deliveryId(),
                first.attempt(),
                new IllegalStateException("late first owner failure"))).isFalse();
        assertThat(deliveryService.markFailed(
                second.deliveryId(),
                second.attempt(),
                new IllegalStateException("current owner failure"))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM notification_delivery WHERE id = ?",
                Integer.class,
                second.deliveryId())).isEqualTo(second.attempt());
    }

    @Test
    void concurrentIdenticalDeliveryRetryCommandsReturnOneAuditedResult() throws Exception {
        service.saveEmailPreference(CUSTOMER_ID, "reader@example.com", true);
        service.acceptDomainEvent(paymentEvent(UUID.randomUUID().toString()), "notification-test");
        long deliveryId = jdbc.queryForObject(
                "SELECT id FROM notification_delivery",
                Long.class);
        jdbc.update("""
                UPDATE notification_delivery
                SET status = 'NEEDS_ATTENTION', attempts = 2
                WHERE id = ?
                """, deliveryId);

        List<DeliveryRetryView> results = runConcurrently(
                8,
                () -> service.retryEmailDelivery(
                        ADMIN_ID,
                        deliveryId,
                        "retry-email-concurrent-command",
                        "SMTP recovered after the concurrent retry test"));

        assertThat(results)
                .extracting(DeliveryRetryView::deliveryId)
                .containsOnly(deliveryId);
        assertThat(results)
                .extracting(DeliveryRetryView::commandId)
                .containsOnly("retry-email-concurrent-command");
        assertThat(count("notification_delivery_retry_audit")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE id = ?",
                String.class,
                deliveryId)).isEqualTo("RETRY");
    }

    private DomainEvent paymentEvent(String eventId) {
        return new DomainEvent(
                eventId,
                "PaymentSucceeded",
                CUSTOMER_ID,
                objectMapper.valueToTree(Map.of(
                        "paymentNo", "PAY-20260724-1",
                        "orderNo", "ORDER-20260724-1",
                        "userId", CUSTOMER_ID)));
    }

    private RequestPostProcessor customerJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId))
                        .claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor adminJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId))
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, participants)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent test start timed out");
                        }
                        return action.call();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Concurrent notification task failed", exception);
                }
            }).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
