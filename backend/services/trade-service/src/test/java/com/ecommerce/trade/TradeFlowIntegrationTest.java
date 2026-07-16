package com.ecommerce.trade;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.CreateOrderCommand;
import com.ecommerce.trade.application.model.TradeModels.FulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderLineCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.application.model.TradeModels.PaymentSucceededCommand;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.ecommerce.trade.infrastructure.messaging.OutboxProperties;
import com.ecommerce.trade.infrastructure.messaging.OutboxPublisherJob;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class TradeFlowIntegrationTest {

    @MockitoBean
    private CatalogPort catalogPort;

    @MockitoBean
    private InventoryPort inventoryPort;

    @MockitoBean
    private AddressPort addressPort;

    @MockitoBean
    private MarketingPort marketingPort;

    private final TradeOrderService orderService;
    private final OutboxEventMapper outboxMapper;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    TradeFlowIntegrationTest(
            TradeOrderService orderService,
            OutboxEventMapper outboxMapper,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.outboxMapper = outboxMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void stubDependencies() {
        reset(catalogPort, inventoryPort, addressPort, marketingPort);
        when(addressPort.getAddress(1L, 501L)).thenReturn(address("Old Street 1"));
        when(catalogPort.getProduct(1L)).thenReturn(product());
        when(inventoryPort.getWarehouse("PRIMARY"))
                .thenReturn(new InventoryPort.WarehouseSnapshot(10L, "PRIMARY", "ACTIVE"));
        when(inventoryPort.reserve(any())).thenAnswer(invocation -> {
            InventoryPort.ReservationCommand command = invocation.getArgument(0);
            return new InventoryPort.ReservationSnapshot(command.reservationNo(), "RESERVED", command.warehouseId());
        });
        when(inventoryPort.release(anyString())).thenAnswer(invocation ->
                new InventoryPort.ReservationSnapshot(invocation.getArgument(0), "RELEASED", 10L));
        doAnswer(invocation -> {
            MarketingPort.PricingCommand command = invocation.getArgument(0);
            return new MarketingPort.PricingLock(
                    "MKT-" + command.orderNo(), command.orderNo(), command.userId(), command.originalAmount(),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), command.originalAmount(), "LOCKED", List.of());
        }).when(marketingPort).lockPricing(any());
    }

    @AfterEach
    void cleanTradeData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM after_sale_history");
        jdbcTemplate.update("DELETE FROM after_sale_item");
        jdbcTemplate.update("DELETE FROM after_sale_order");
        jdbcTemplate.update("DELETE FROM order_status_history");
        jdbcTemplate.update("DELETE FROM order_address_snapshot");
        jdbcTemplate.update("DELETE FROM order_discount_allocation");
        jdbcTemplate.update("DELETE FROM order_price_snapshot");
        jdbcTemplate.update("DELETE FROM order_benefit_selection");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM trade_order");
        jdbcTemplate.update("DELETE FROM cart_item");
    }

    @Test
    void securesAndIsolatesCartItemsByJwtSubject() throws Exception {
        String body = objectMapper.writeValueAsString(new CartRequest(1L, 2, true));
        mockMvc.perform(put("/api/v1/trade/cart/items/101")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/v1/trade/cart/items/101")
                        .with(customerJwt("1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productTitle").value("Test Product"))
                .andExpect(jsonPath("$.data.quantity").value(2));

        mockMvc.perform(get("/api/v1/trade/cart/items").with(customerJwt("2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void storesImmutableSnapshotsAndMovesToPendingPayment() {
        OrderView order = orderService.createOrder(command(1L, "idem-order-001", 2));

        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.totalAmount()).isEqualByComparingTo("39.80");
        assertThat(order.deliveryAddress().detailAddress()).isEqualTo("Old Street 1");
        assertThat(order.priceSnapshot().originalAmount()).isEqualByComparingTo("39.80");
        assertThat(order.priceSnapshot().payableAmount()).isEqualByComparingTo("39.80");
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.productTitle()).isEqualTo("Test Product");
            assertThat(item.skuCode()).isEqualTo("SKU-101");
            assertThat(item.unitPrice()).isEqualByComparingTo("19.90");
            assertThat(item.lineAmount()).isEqualByComparingTo("39.80");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_status_history", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void storesImmutableDiscountAndAllocationSnapshotsBeforeReservingStock() {
        doAnswer(invocation -> {
            MarketingPort.PricingCommand command = invocation.getArgument(0);
            List<MarketingPort.AppliedBenefit> benefits = List.of(
                    applied("BEN-COUPON", "COUPON-5", "COUPON", "5.00", command),
                    applied("BEN-RED", "RED-2", "RED_PACKET", "2.00", command),
                    applied("BEN-SUBSIDY", "SUBSIDY-1", "SUBSIDY", "1.00", command));
            return new MarketingPort.PricingLock(
                    "MKT-DISCOUNT", command.orderNo(), command.userId(), command.originalAmount(),
                    new BigDecimal("5.00"), new BigDecimal("2.00"), new BigDecimal("1.00"),
                    new BigDecimal("8.00"), new BigDecimal("31.80"), "LOCKED", benefits);
        }).when(marketingPort).lockPricing(any());
        CreateOrderCommand command = new CreateOrderCommand(1L, "idem-discount-001", 501L,
                List.of(new OrderLineCommand(1L, 101L, 2)),
                List.of("BEN-COUPON", "BEN-RED", "BEN-SUBSIDY"));

        OrderView order = orderService.createOrder(command);

        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.totalAmount()).isEqualByComparingTo("31.80");
        assertThat(order.priceSnapshot().originalAmount()).isEqualByComparingTo("39.80");
        assertThat(order.priceSnapshot().discountAmount()).isEqualByComparingTo("8.00");
        assertThat(order.priceSnapshot().allocations()).hasSize(3);
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.lineAmount()).isEqualByComparingTo("39.80");
            assertThat(item.discountAmount()).isEqualByComparingTo("8.00");
            assertThat(item.payableAmount()).isEqualByComparingTo("31.80");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_discount_allocation", Integer.class)).isEqualTo(3);
        verify(inventoryPort, times(1)).reserve(any());
    }

    @Test
    void keepsTheOrderAddressImmutableWhenTheSourceAddressChanges() {
        OrderView created = orderService.createOrder(command(1L, "idem-address-snapshot-001", 1));
        when(addressPort.getAddress(1L, 501L)).thenReturn(address("New Street 99"));

        OrderView stored = orderService.getOrder(1L, created.orderNo());

        assertThat(stored.deliveryAddress().sourceAddressId()).isEqualTo(501L);
        assertThat(stored.deliveryAddress().recipientName()).isEqualTo("Test Customer");
        assertThat(stored.deliveryAddress().detailAddress()).isEqualTo("Old Street 1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_address_snapshot", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void closesAnOrderWhenInventoryRejectsIt() {
        doAnswer(invocation -> {
            InventoryPort.ReservationCommand command = invocation.getArgument(0);
            return new InventoryPort.ReservationSnapshot(command.reservationNo(), "REJECTED", command.warehouseId());
        }).when(inventoryPort).reserve(any());

        OrderView order = orderService.createOrder(command(1L, "idem-shortage-001", 1));

        assertThat(order.status()).isEqualTo("CLOSED");
        assertThat(order.closeReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void retainsPendingStockAndRecoversAfterInventoryOutage() {
        doThrow(new IllegalStateException("inventory unavailable")).when(inventoryPort).reserve(any());
        doThrow(new IllegalStateException("query unavailable")).when(inventoryPort).getReservation(anyString());

        OrderView pending = orderService.createOrder(command(1L, "idem-recovery-001", 1));
        assertThat(pending.status()).isEqualTo("PENDING_STOCK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recovery_attempts FROM trade_order WHERE order_no = ?", Integer.class, pending.orderNo()))
                .isEqualTo(1);

        doAnswer(invocation -> {
            InventoryPort.ReservationCommand command = invocation.getArgument(0);
            return new InventoryPort.ReservationSnapshot(command.reservationNo(), "RESERVED", command.warehouseId());
        }).when(inventoryPort).reserve(any());
        orderService.recoverOrder(pending.orderNo());

        assertThat(orderService.getOrder(1L, pending.orderNo()).status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void doesNotReserveStockUntilMarketingRecovers() {
        doThrow(new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE))
                .when(marketingPort).lockPricing(any());

        OrderView pending = orderService.createOrder(command(1L, "idem-marketing-recovery-001", 1));

        assertThat(pending.status()).isEqualTo("PENDING_STOCK");
        assertThat(pending.priceSnapshot()).isNull();
        verify(inventoryPort, times(0)).reserve(any());

        doAnswer(invocation -> {
            MarketingPort.PricingCommand pricing = invocation.getArgument(0);
            return new MarketingPort.PricingLock(
                    "MKT-RECOVERED", pricing.orderNo(), pricing.userId(), pricing.originalAmount(),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), pricing.originalAmount(), "LOCKED", List.of());
        }).when(marketingPort).lockPricing(any());
        orderService.recoverOrder(pending.orderNo());

        assertThat(orderService.getOrder(1L, pending.orderNo()).status()).isEqualTo("PENDING_PAYMENT");
        verify(inventoryPort, times(1)).reserve(any());
    }

    @Test
    void closesMarketingRejectionWithoutTouchingInventory() {
        doThrow(new MarketingPort.PricingRejectedException("not eligible"))
                .when(marketingPort).lockPricing(any());

        OrderView closed = orderService.createOrder(command(1L, "idem-marketing-reject-001", 1));

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closeReason()).isEqualTo("MARKETING_REJECTED");
        assertThat(closed.priceSnapshot()).isNull();
        verify(inventoryPort, times(0)).reserve(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderClosed'", Integer.class)).isEqualTo(1);
    }

    @Test
    void createsOnlyOneOrderForFiftyConcurrentIdenticalCommands() throws Exception {
        CreateOrderCommand command = command(1L, "idem-concurrent-001", 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return orderService.createOrder(command).orderNo();
                    })).toList();
            start.countDown();
            Set<String> orderNumbers = new java.util.HashSet<>();
            for (Future<String> future : futures) {
                orderNumbers.add(future.get());
            }
            assertThat(orderNumbers).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class)).isEqualTo(1);
        verify(marketingPort, times(1)).lockPricing(any());
        verify(inventoryPort, times(1)).reserve(any());
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForDifferentItems() {
        orderService.createOrder(command(1L, "idem-conflict-001", 1));

        assertThatThrownBy(() -> orderService.createOrder(command(1L, "idem-conflict-001", 2)))
                .isInstanceOf(TradeException.class)
                .satisfies(exception -> assertThat(((TradeException) exception).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancellationReleasesInventoryAndIsIdempotent() {
        OrderView created = orderService.createOrder(command(1L, "idem-cancel-001", 1));

        OrderView canceled = orderService.cancelOrder(1L, created.orderNo());
        OrderView repeated = orderService.cancelOrder(1L, created.orderNo());

        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(repeated.status()).isEqualTo("CANCELED");
        verify(inventoryPort, times(1)).release(anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_status_history", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void appliesPaymentEventOnceAndPublishesOrderPaid() {
        OrderView created = orderService.createOrder(command(1L, "idem-paid-001", 2));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        PaymentSucceededCommand payment = new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000101",
                "PAY-101",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("39.80"));

        orderService.applyPaymentSucceeded(payment);
        orderService.applyPaymentSucceeded(payment);

        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'", Integer.class)).isEqualTo(1);
    }

    @Test
    void advancesPaidOrderFromFulfillmentEventsExactlyOnce() {
        OrderView created = orderService.createOrder(command(1L, "idem-fulfillment-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000111", "PAY-111", created.orderNo(),
                1L, reservationNo, new BigDecimal("19.90")));

        FulfillmentEventCommand fulfillmentCreated = new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000112", "FulfillmentCreated",
                "FUL-111", created.orderNo(), 1L);
        orderService.applyFulfillmentEvent(fulfillmentCreated);
        orderService.applyFulfillmentEvent(fulfillmentCreated);
        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("FULFILLING");

        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000113", "ShipmentDispatched",
                "FUL-111", created.orderNo(), 1L));
        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("SHIPPED");

        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000114", "ShipmentSigned",
                "FUL-111", created.orderNo(), 1L));
        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE consumer_group = 'trade-fulfillment-events-v1'",
                Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type IN ('OrderFulfilling','OrderShipped','OrderCompleted')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void retriesOutOfOrderFulfillmentEventsWithoutConsumingThem() {
        OrderView created = orderService.createOrder(command(1L, "idem-fulfillment-ordering-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000121", "PAY-121", created.orderNo(),
                1L, reservationNo, new BigDecimal("19.90")));
        FulfillmentEventCommand earlyShipment = new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000122", "ShipmentDispatched",
                "FUL-121", created.orderNo(), 1L);

        assertThatThrownBy(() -> orderService.applyFulfillmentEvent(earlyShipment))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.INVALID_STATE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_id = '00000000-0000-0000-0000-000000000122'",
                Integer.class)).isZero();

        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000123", "FulfillmentCreated",
                "FUL-121", created.orderNo(), 1L));
        orderService.applyFulfillmentEvent(earlyShipment);
        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("SHIPPED");
    }

    @Test
    void flagsLatePaymentAfterCancellationWithoutConfirmingInventory() {
        OrderView created = orderService.createOrder(command(1L, "idem-late-payment-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        orderService.cancelOrder(1L, created.orderNo());

        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000102",
                "PAY-LATE-102",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90")));

        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("PAYMENT_EXCEPTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentReviewRequired'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exposesPaymentContextOnlyToTheTrustedPaymentService() throws Exception {
        OrderView created = orderService.createOrder(command(1L, "idem-context-001", 1));
        String path = "/api/v1/trade/internal/orders/" + created.orderNo() + "/payment-context";

        mockMvc.perform(get(path).with(customerJwt("1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path)
                        .header("X-Internal-Service", "payment-service")
                        .header("X-Internal-Token", "test-internal-service-token-1234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    void paymentTimeoutUsesTheSameRecoverableCancellationFlow() {
        OrderView created = orderService.createOrder(command(1L, "idem-timeout-001", 1));
        jdbcTemplate.update("UPDATE trade_order SET payment_deadline = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE order_no = ?",
                created.orderNo());

        assertThat(orderService.findTimedOutOrderNumbers(10)).containsExactly(created.orderNo());
        orderService.cancelTimedOutOrder(created.orderNo());

        OrderView canceled = orderService.getOrder(1L, created.orderNo());
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.closeReason()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    void retriesTradeOutboxPublicationWithoutLosingEvents() throws Exception {
        orderService.createOrder(command(1L, "idem-outbox-001", 1));
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(publisher).publish(anyString(), anyString(), anyString());
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-order-events", 2000,
                Duration.ZERO, 100);
        Clock futureClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        OutboxPublisherJob job = new OutboxPublisherJob(outboxMapper, publisher, properties, futureClock);

        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(1);

        doNothing().when(publisher).publish(anyString(), anyString(), anyString());
        job.publishPendingEvents();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(2);
    }

    private CreateOrderCommand command(Long userId, String key, long quantity) {
        return new CreateOrderCommand(userId, key, 501L,
                List.of(new OrderLineCommand(1L, 101L, quantity)), List.of());
    }

    private MarketingPort.AppliedBenefit applied(
            String benefitNo,
            String ruleCode,
            String type,
            String amount,
            MarketingPort.PricingCommand command) {
        BigDecimal discount = new BigDecimal(amount);
        MarketingPort.PricingLine line = command.lines().get(0);
        return new MarketingPort.AppliedBenefit(benefitNo, ruleCode, type, discount,
                List.of(new MarketingPort.DiscountAllocation(
                        line.lineNo(), line.skuId(), benefitNo, ruleCode, type, discount)));
    }

    private AddressPort.AddressSnapshot address(String detailAddress) {
        return new AddressPort.AddressSnapshot(
                501L, "Test Customer", "+86 13800000000", "Zhejiang", "330000",
                "Hangzhou", "330100", "Xihu", "330106", detailAddress, "310000");
    }

    private CatalogPort.ProductSnapshot product() {
        return new CatalogPort.ProductSnapshot(
                1L, "Test Product", "ACTIVE",
                List.of(new CatalogPort.SkuSnapshot(
                        101L, "SKU-101", "Default", "{\"size\":\"M\"}",
                        new BigDecimal("19.90"), "ACTIVE")),
                List.of(new CatalogPort.MediaSnapshot(101L, "products/test.png", 0)));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt(String subject) {
        return jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private record CartRequest(Long productId, long quantity, boolean selected) {
    }
}
