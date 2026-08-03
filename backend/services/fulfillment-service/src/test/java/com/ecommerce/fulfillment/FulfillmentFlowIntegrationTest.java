package com.ecommerce.fulfillment;

import com.ecommerce.fulfillment.application.exception.FulfillmentError;
import com.ecommerce.fulfillment.application.exception.FulfillmentException;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AddTraceCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.DeliveryAddress;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.FulfillmentView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.OrderPaidCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ShipCommand;
import com.ecommerce.fulfillment.application.port.DomainEventPublisher;
import com.ecommerce.fulfillment.application.service.FulfillmentService;
import com.ecommerce.fulfillment.infrastructure.messaging.OutboxProperties;
import com.ecommerce.fulfillment.infrastructure.messaging.OutboxPublisherJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class FulfillmentFlowIntegrationTest {

    private final FulfillmentService fulfillmentService;
    private final OutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    FulfillmentFlowIntegrationTest(
            FulfillmentService fulfillmentService,
            OutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.fulfillmentService = fulfillmentService;
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
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
    void createsOneFulfillmentForDuplicateOrderPaidEvents() {
        OrderPaidCommand event = new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000301", "ORDER-301", 31L, address());

        FulfillmentView first = fulfillmentService.createFromOrderPaid(event);
        FulfillmentView repeated = fulfillmentService.createFromOrderPaid(event);

        assertThat(repeated.fulfillmentNo()).isEqualTo(first.fulfillmentNo());
        assertThat(repeated.status()).isEqualTo("CREATED");
        assertThat(repeated.deliveryAddress().detailAddress()).isEqualTo("Old Street 1");
        assertThat(repeated.deliveryAddress().districtCode()).isEqualTo("330106");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fulfillment_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'FulfillmentCreated'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CHAR_LENGTH(payload_fingerprint) FROM consumed_event", Integer.class))
                .isEqualTo(64);
    }

    @Test
    void rejectsOrderPaidEventIdReuseWithDifferentPayload() {
        String eventId = "00000000-0000-0000-0000-000000000399";
        OrderPaidCommand original = new OrderPaidCommand(eventId, "ORDER-399", 39L, address());
        fulfillmentService.createFromOrderPaid(original);

        DeliveryAddress changedAddress = new DeliveryAddress(
                501L, "Test Customer", "+86 13800000000", "Zhejiang",
                "330000", "Hangzhou", "330100", "Xihu", "330106",
                "Changed Street 9", "310000");
        assertThatThrownBy(() -> fulfillmentService.createFromOrderPaid(
                new OrderPaidCommand(eventId, "ORDER-399", 39L, changedAddress)))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> fulfillmentService.createFromOrderPaid(
                new OrderPaidCommand(eventId, "ORDER-OTHER", 39L, address())))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.IDEMPOTENCY_CONFLICT));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fulfillment_order", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsUnverifiableLegacyOrderPaidEventIdentity() {
        OrderPaidCommand command = new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000398", "ORDER-398", 38L, address());
        fulfillmentService.createFromOrderPaid(command);
        jdbcTemplate.update(
                "UPDATE consumed_event SET payload_fingerprint = NULL WHERE event_id = ?",
                command.eventId());

        assertThatThrownBy(() -> fulfillmentService.createFromOrderPaid(command))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void completesTheFulfillmentAndAppendOnlyLogisticsFlow() {
        FulfillmentView created = create("302", 32L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markPacked(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.ship(created.fulfillmentNo(),
                new ShipCommand("MOCK_EXPRESS", "TRACK-302", "warehouse-1"));

        AddTraceCommand transit = trace("EVENT-302-1", "TRANSIT", "Arrived at sorting center", "Nanjing");
        fulfillmentService.addTrace(created.fulfillmentNo(), transit);
        fulfillmentService.addTrace(created.fulfillmentNo(), transit);
        fulfillmentService.addTrace(created.fulfillmentNo(),
                trace("EVENT-302-2", "DELIVERING", "Courier is delivering", "Shanghai"));
        FulfillmentView signed = fulfillmentService.addTrace(created.fulfillmentNo(),
                trace("EVENT-302-3", "SIGNED", "Recipient signed", "Shanghai"));

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.history()).hasSize(7);
        assertThat(signed.traces()).hasSize(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_status_history", Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ShipmentSigned'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM logistics_trace WHERE external_event_id = 'EVENT-302-1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsStateSkippingAndConflictingTraceReuse() {
        FulfillmentView created = create("303", 33L);
        assertThatThrownBy(() -> fulfillmentService.markPacked(created.fulfillmentNo(), "warehouse-1"))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.INVALID_STATE));

        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markPacked(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.ship(created.fulfillmentNo(),
                new ShipCommand("MOCK_EXPRESS", "TRACK-303", "warehouse-1"));
        fulfillmentService.addTrace(created.fulfillmentNo(),
                trace("EVENT-303", "TRANSIT", "First content", "Nanjing"));

        assertThatThrownBy(() -> fulfillmentService.addTrace(created.fulfillmentNo(),
                trace("EVENT-303", "TRANSIT", "Changed content", "Nanjing")))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void enforcesWarehouseRoleAndCustomerOwnership() throws Exception {
        FulfillmentView created = create("304", 34L);
        String pickingPath = "/api/v1/fulfillment/admin/orders/" + created.fulfillmentNo() + "/picking";

        mockMvc.perform(post(pickingPath).with(jwt().jwt(token -> token.subject("34"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(pickingPath).with(jwt().jwt(token -> token.subject("warehouse-1"))
                        .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKING"));

        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-304")
                        .with(jwt().jwt(token -> token.subject("35"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-304")
                        .with(jwt().jwt(token -> token.subject("34"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("ORDER-304"))
                .andExpect(jsonPath("$.data.userId").value("34"))
                .andExpect(jsonPath("$.data.deliveryAddress.sourceAddressId").value("501"))
                .andExpect(jsonPath("$.data.history[0].toStatus").value("CREATED"));
    }

    @Test
    void resolvesAnExceptionOnceWithAnAuditedIdempotentAdminCommand() {
        FulfillmentView created = create("305", 35L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markException(
                created.fulfillmentNo(), "Scanner unavailable", "warehouse-1");

        FulfillmentView resolved = fulfillmentService.resolveException(
                created.fulfillmentNo(),
                "resolve-fulfillment-305",
                "Scanner replaced and parcel rechecked",
                "admin-1");
        FulfillmentView replayed = fulfillmentService.resolveException(
                created.fulfillmentNo(),
                "resolve-fulfillment-305",
                "Scanner replaced and parcel rechecked",
                "admin-1");

        assertThat(resolved.status()).isEqualTo("PICKING");
        assertThat(replayed.status()).isEqualTo("PICKING");
        assertThat(replayed.history()).extracting(item -> item.command())
                .containsExactly("CREATE_FULFILLMENT", "START_PICKING",
                        "MARK_EXCEPTION", "RESOLVE_EXCEPTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_exception_resolution",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resume_status FROM fulfillment_exception_resolution",
                String.class)).isEqualTo("PICKING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type = 'FulfillmentExceptionResolved'",
                Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> fulfillmentService.resolveException(
                created.fulfillmentNo(),
                "resolve-fulfillment-305",
                "Changed recovery explanation",
                "admin-1"))
                .isInstanceOf(FulfillmentException.class)
                .satisfies(error -> assertThat(((FulfillmentException) error).error())
                        .isEqualTo(FulfillmentError.IDEMPOTENCY_CONFLICT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_exception_resolution",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void restrictsExceptionResolutionToAdminAtTheOwningService() throws Exception {
        FulfillmentView created = create("395", 350L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markException(
                created.fulfillmentNo(), "Manual review required", "warehouse-1");
        String path = "/api/v1/fulfillment/admin/orders/"
                + created.fulfillmentNo() + "/exception/resolve";

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", "resolve-fulfillment-305a")
                        .contentType("application/json")
                        .content("{\"reason\":\"Reviewed against the physical parcel\"}")
                        .with(jwt().jwt(token -> token.subject("warehouse-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_exception_resolution",
                Integer.class)).isZero();
        assertThat(fulfillmentService.get(created.fulfillmentNo()).status())
                .isEqualTo("EXCEPTION");

        mockMvc.perform(post(path)
                        .header("Idempotency-Key", "resolve-fulfillment-305a")
                        .contentType("application/json")
                        .content("{\"reason\":\"Reviewed against the physical parcel\"}")
                        .with(jwt().jwt(token -> token.subject("admin-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKING"))
                .andExpect(jsonPath("$.data.history[3].operatorType").value("ADMIN"))
                .andExpect(jsonPath("$.data.history[3].command").value("RESOLVE_EXCEPTION"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_exception_resolution",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentExceptionResolutionCommandsProduceOneTransitionAndOneAudit() throws Exception {
        FulfillmentView created = create("396", 396L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markException(
                created.fulfillmentNo(), "Parcel requires a decision", "warehouse-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> resolveConcurrently(
                    created.fulfillmentNo(), "resolve-fulfillment-396-a",
                    "Physical parcel checked", ready, start));
            Future<String> second = executor.submit(() -> resolveConcurrently(
                    created.fulfillmentNo(), "resolve-fulfillment-396-b",
                    "Physical parcel checked", ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("PICKING", "INVALID_STATE");
        } finally {
            executor.shutdownNow();
        }

        assertThat(fulfillmentService.get(created.fulfillmentNo()).status())
                .isEqualTo("PICKING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_exception_resolution",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_status_history "
                        + "WHERE command = 'RESOLVE_EXCEPTION'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type = 'FulfillmentExceptionResolved'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void letsTheOwnerConfirmReceiptIdempotentlyAndPublishesOneSignedEvent() throws Exception {
        FulfillmentView created = create("306", 36L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markPacked(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.ship(created.fulfillmentNo(),
                new ShipCommand("MOCK_EXPRESS", "TRACK-306", "warehouse-1"));

        String path = "/api/v1/fulfillment/orders/ORDER-306/confirm-receipt";
        mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject("37"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject("36"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNED"))
                .andExpect(jsonPath("$.data.userId").value("36"))
                .andExpect(jsonPath("$.data.history[4].command").value("CONFIRM_RECEIPT"))
                .andExpect(jsonPath("$.data.traces[0].nodeType").value("SIGNED"));
        mockMvc.perform(post(path).with(jwt().jwt(token -> token.subject("36"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM logistics_trace WHERE external_event_id = 'customer-confirm:ORDER-306'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ShipmentSigned'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void projectsTheLatestShipmentPositionAndProtectsGeoQueries() throws Exception {
        FulfillmentView created = create("307", 37L);
        fulfillmentService.startPicking(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.markPacked(created.fulfillmentNo(), "warehouse-1");
        fulfillmentService.ship(created.fulfillmentNo(),
                new ShipCommand("MOCK_EXPRESS", "TRACK-307", "warehouse-1"));

        Instant nanjingAt = Instant.parse("2026-07-24T08:00:00Z");
        Instant shanghaiAt = Instant.parse("2026-07-24T10:00:00Z");
        fulfillmentService.addTrace(created.fulfillmentNo(), new AddTraceCommand(
                "EVENT-307-1", "TRANSIT", "Arrived at Nanjing", "Nanjing",
                new BigDecimal("118.796877"), new BigDecimal("32.060255"),
                nanjingAt, "carrier-mock"));
        fulfillmentService.addTrace(created.fulfillmentNo(), new AddTraceCommand(
                "EVENT-307-2", "DELIVERING", "Courier is delivering", "Shanghai",
                new BigDecimal("121.473700"), new BigDecimal("31.230400"),
                shanghaiAt, "carrier-mock"));
        fulfillmentService.addTrace(created.fulfillmentNo(), new AddTraceCommand(
                "EVENT-307-LATE", "DELIVERING", "Delayed older carrier event", "Suzhou",
                new BigDecimal("120.585300"), new BigDecimal("31.298900"),
                nanjingAt.plusSeconds(600), "carrier-mock"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shipment_latest_position", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT external_event_id FROM shipment_latest_position", String.class))
                .isEqualTo("EVENT-307-2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT latest_position_trace_id FROM fulfillment_order WHERE fulfillment_no = ?",
                Long.class, created.fulfillmentNo())).isNotNull();

        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-307/position")
                        .with(jwt().jwt(token -> token.subject("38"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-307/position")
                        .with(jwt().jwt(token -> token.subject("37"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalEventId").value("EVENT-307-2"))
                .andExpect(jsonPath("$.data.locationName").value("Shanghai"))
                .andExpect(jsonPath("$.data.longitude").value(121.473700))
                .andExpect(jsonPath("$.data.latitude").value(31.230400));

        String nearbyPath = "/api/v1/fulfillment/admin/geo/nearby"
                + "?longitude=121.473700&latitude=31.230400&radiusMeters=10000&limit=10";
        mockMvc.perform(get(nearbyPath)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(nearbyPath)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fulfillmentNo").value(created.fulfillmentNo()))
                .andExpect(jsonPath("$.data[0].distanceMeters").value(0.00));
    }

    @Test
    void reportsMissingPositionsAndRejectsOversizedGeoQueries() throws Exception {
        create("308", 38L);

        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-308/position")
                        .with(jwt().jwt(token -> token.subject("38"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSITION_NOT_AVAILABLE"));
        mockMvc.perform(get("/api/v1/fulfillment/admin/geo/nearby"
                        + "?longitude=120&latitude=30&radiusMeters=2000001&limit=10")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEO_QUERY"));
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
                .andExpect(jsonPath("$.service").value("fulfillment-service"));
    }

    @Test
    void retainsFailedOutboxEventsAndPublishesThemOnRetry() throws Exception {
        create("305", 35L);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable")).doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-logistics-events", 2000,
                Duration.ZERO, 50, "fulfillment-test-job", Duration.ofSeconds(30));
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxPublisherJob job = new OutboxPublisherJob(
                outboxMapper, publisher, properties, futureClock, meterRegistry);

        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                .isEqualTo("PENDING");
        assertThat(meterRegistry.get("ecommerce.outbox.publications")
                .tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isEqualTo(1);
        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(meterRegistry.get("ecommerce.outbox.publications")
                .tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isZero();
    }

    private FulfillmentView create(String suffix, Long userId) {
        return fulfillmentService.createFromOrderPaid(new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000" + suffix,
                "ORDER-" + suffix,
                userId,
                address()));
    }

    private String resolveConcurrently(
            String fulfillmentNo,
            String commandId,
            String reason,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return fulfillmentService.resolveException(
                    fulfillmentNo, commandId, reason, "admin-1").status();
        } catch (FulfillmentException exception) {
            return exception.error().name();
        }
    }

    private DeliveryAddress address() {
        return new DeliveryAddress(501L, "Test Customer", "+86 13800000000", "Zhejiang",
                "330000", "Hangzhou", "330100", "Xihu", "330106", "Old Street 1", "310000");
    }

    private AddTraceCommand trace(String eventId, String nodeType, String description, String location) {
        return new AddTraceCommand(eventId, nodeType, description, location,
                new BigDecimal("118.796877"), new BigDecimal("32.060255"),
                Instant.now(), "carrier-mock");
    }
}
