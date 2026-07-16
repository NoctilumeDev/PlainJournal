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
        jdbcTemplate.update("DELETE FROM logistics_trace");
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/fulfillment/orders/ORDER-304")
                        .with(jwt().jwt(token -> token.subject("34"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("ORDER-304"));
    }

    @Test
    void retainsFailedOutboxEventsAndPublishesThemOnRetry() throws Exception {
        create("305", 35L);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable")).doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-logistics-events", 2000, Duration.ZERO, 50);
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        OutboxPublisherJob job = new OutboxPublisherJob(outboxMapper, publisher, properties, futureClock);

        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                .isEqualTo("PENDING");
        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_event", String.class))
                .isEqualTo("PUBLISHED");
    }

    private FulfillmentView create(String suffix, Long userId) {
        return fulfillmentService.createFromOrderPaid(new OrderPaidCommand(
                "00000000-0000-0000-0000-000000000" + suffix,
                "ORDER-" + suffix,
                userId,
                address()));
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
