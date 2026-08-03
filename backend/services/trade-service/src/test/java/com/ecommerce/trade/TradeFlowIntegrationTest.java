package com.ecommerce.trade;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.CreateOrderCommand;
import com.ecommerce.trade.application.model.TradeModels.FulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.GuestBagItemCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderLineCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.application.model.TradeModels.PaymentSucceededCommand;
import com.ecommerce.trade.application.model.TradeModels.RefundEventCommand;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.ecommerce.trade.application.service.CartService;
import com.ecommerce.trade.infrastructure.messaging.OutboxProperties;
import com.ecommerce.trade.infrastructure.messaging.OutboxClaimService;
import com.ecommerce.trade.infrastructure.messaging.OutboxPublisherJob;
import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.trade.infrastructure.id.DistributedIdWorkerLeaseManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private final CartService cartService;
    private final OutboxEventMapper outboxMapper;
    private final OutboxClaimService outboxClaimService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final DistributedIdWorkerLeaseManager distributedIdWorkerLeaseManager;
    private final TradeShardRouter shardRouter;

    @Autowired
    TradeFlowIntegrationTest(
            TradeOrderService orderService,
            CartService cartService,
            OutboxEventMapper outboxMapper,
            OutboxClaimService outboxClaimService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            DistributedIdWorkerLeaseManager distributedIdWorkerLeaseManager,
            TradeShardRouter shardRouter) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.outboxMapper = outboxMapper;
        this.outboxClaimService = outboxClaimService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.distributedIdWorkerLeaseManager = distributedIdWorkerLeaseManager;
        this.shardRouter = shardRouter;
    }

    @Test
    void standardOrderPrimaryKeyUsesTheLeasedDistributedIdWorker() {
        OrderView order = orderService.createOrder(command(1L, "idem-distributed-id-001", 1));
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM trade_order WHERE order_no = ?",
                Long.class,
                order.orderNo());

        assertThat(order.orderNo()).isEqualTo("ORD" + orderId);
        assertThat(DistributedIdGenerator.workerIdOf(orderId))
                .isEqualTo(distributedIdWorkerLeaseManager.workerId());
    }

    @BeforeEach
    void stubDependencies() {
        reset(catalogPort, inventoryPort, addressPort, marketingPort);
        when(addressPort.getAddress(1L, 501L)).thenAnswer(ignored -> {
            assertRemoteCallOutsideLocalTransaction();
            return address("Old Street 1");
        });
        when(catalogPort.getProduct(1L)).thenAnswer(ignored -> {
            assertRemoteCallOutsideLocalTransaction();
            return product();
        });
        when(inventoryPort.getWarehouse("PRIMARY"))
                .thenAnswer(ignored -> {
                    assertRemoteCallOutsideLocalTransaction();
                    return new InventoryPort.WarehouseSnapshot(10L, "PRIMARY", "ACTIVE");
                });
        when(inventoryPort.reserve(any())).thenAnswer(invocation -> {
            assertRemoteCallOutsideLocalTransaction();
            InventoryPort.ReservationCommand command = invocation.getArgument(0);
            return reservation(command, "RESERVED");
        });
        when(inventoryPort.confirm(anyString())).thenAnswer(invocation -> {
            assertRemoteCallOutsideLocalTransaction();
            return storedReservation(invocation.getArgument(0), "CONFIRMED");
        });
        when(inventoryPort.release(anyString())).thenAnswer(invocation -> {
            assertRemoteCallOutsideLocalTransaction();
            return releasedReservation(invocation.getArgument(0));
        });
        doAnswer(invocation -> {
            assertRemoteCallOutsideLocalTransaction();
            MarketingPort.PricingCommand command = invocation.getArgument(0);
            return new MarketingPort.PricingLock(
                    "MKT-" + command.orderNo(), command.orderNo(), command.userId(), command.originalAmount(),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), command.originalAmount(), "LOCKED", List.of());
        }).when(marketingPort).lockPricing(any());
    }

    private void assertRemoteCallOutsideLocalTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
        jdbcTemplate.update("DELETE FROM cart_merge_request");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM cart_user_lock");
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
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.productId").isString())
                .andExpect(jsonPath("$.data.skuId").isString())
                .andExpect(jsonPath("$.data.productTitle").value("Test Product"))
                .andExpect(jsonPath("$.data.quantity").value(2));

        mockMvc.perform(get("/api/v1/trade/cart/items").with(customerJwt("2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void preservesTheCartDisplaySnapshotWhenCatalogChangesLater() {
        var created = cartService.putItem(1L, 1L, 101L, 2, true);
        reset(catalogPort);

        var listed = cartService.listItems(1L);

        assertThat(created.unitPrice()).isEqualByComparingTo("19.90");
        assertThat(listed).singleElement().satisfies(item -> {
            assertThat(item.productTitle()).isEqualTo("Test Product");
            assertThat(item.skuName()).isEqualTo("Default");
            assertThat(item.specJson()).isEqualTo("{\"size\":\"M\"}");
            assertThat(item.unitPrice()).isEqualByComparingTo("19.90");
        });
        verify(catalogPort, times(0)).getProduct(any());
    }

    @Test
    void mergesGuestBagWithoutOverwritingAndReplaysTheSameRequestSafely() throws Exception {
        String existing = objectMapper.writeValueAsString(new CartRequest(1L, 3, false));
        mockMvc.perform(put("/api/v1/trade/cart/items/101")
                        .with(customerJwt("1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(existing))
                .andExpect(status().isOk());

        String merge = objectMapper.writeValueAsString(java.util.Map.of(
                "items", List.of(java.util.Map.of(
                        "productId", "1",
                        "skuId", "101",
                        "quantity", 2))));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/trade/cart/guest-merge")
                            .with(customerJwt("1"))
                            .header("Idempotency-Key", "guest-merge-device-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(merge))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").isString())
                    .andExpect(jsonPath("$.data[0].productId").value("1"))
                    .andExpect(jsonPath("$.data[0].skuId").value("101"))
                    .andExpect(jsonPath("$.data[0].quantity").value(5))
                    .andExpect(jsonPath("$.data[0].selected").value(true));
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart_item WHERE user_id = 1 AND sku_id = 101",
                Long.class)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_merge_request WHERE user_id = 1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsReusingGuestBagMergeKeyWithDifferentPayload() throws Exception {
        String first = objectMapper.writeValueAsString(java.util.Map.of(
                "items", List.of(java.util.Map.of(
                        "productId", "1",
                        "skuId", "101",
                        "quantity", 1))));
        String second = objectMapper.writeValueAsString(java.util.Map.of(
                "items", List.of(java.util.Map.of(
                        "productId", "1",
                        "skuId", "101",
                        "quantity", 2))));

        mockMvc.perform(post("/api/v1/trade/cart/guest-merge")
                        .with(customerJwt("1"))
                        .header("Idempotency-Key", "guest-merge-device-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/trade/cart/guest-merge")
                        .with(customerJwt("1"))
                        .header("Idempotency-Key", "guest-merge-device-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart_item WHERE user_id = 1 AND sku_id = 101",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void appliesOneGuestBagMergeForConcurrentRetries() throws Exception {
        List<GuestBagItemCommand> items = List.of(new GuestBagItemCommand(1L, 101L, 2));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<Long>> futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return cartService.mergeGuestBag(
                                1L, "guest-merge-concurrent-001", items).get(0).quantity();
                    }))
                    .toList();
            start.countDown();
            for (Future<Long> future : futures) {
                assertThat(future.get()).isEqualTo(2L);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM cart_item WHERE user_id = 1 AND sku_id = 101",
                Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_merge_request WHERE user_id = 1",
                Integer.class)).isEqualTo(1);
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
        JsonNode browserView = objectMapper.valueToTree(order);
        assertThat(browserView.at("/deliveryAddress/sourceAddressId").isTextual()).isTrue();
        assertThat(browserView.at("/items/0/productId").isTextual()).isTrue();
        assertThat(browserView.at("/items/0/skuId").isTextual()).isTrue();
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
        JsonNode browserView = objectMapper.valueToTree(order);
        assertThat(browserView.at("/priceSnapshot/allocations/0/skuId").isTextual()).isTrue();
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
            return reservation(command, "REJECTED");
        }).when(inventoryPort).reserve(any());

        OrderView order = orderService.createOrder(command(1L, "idem-shortage-001", 1));

        assertThat(order.status()).isEqualTo("CLOSED");
        assertThat(order.closeReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void retainsPendingStockAndRecoversAfterInventoryOutage() {
        double unresolvedBefore = resolutionCount("unresolved");
        doThrow(new IllegalStateException("inventory unavailable")).when(inventoryPort).reserve(any());
        doThrow(new IllegalStateException("query unavailable")).when(inventoryPort).getReservation(anyString());

        OrderView pending = orderService.createOrder(command(1L, "idem-recovery-001", 1));
        assertThat(pending.status()).isEqualTo("PENDING_STOCK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recovery_attempts FROM trade_order WHERE order_no = ?", Integer.class, pending.orderNo()))
                .isEqualTo(1);
        assertThat(resolutionCount("unresolved")).isEqualTo(unresolvedBefore + 1.0d);

        doAnswer(invocation -> {
            InventoryPort.ReservationCommand command = invocation.getArgument(0);
            return reservation(command, "RESERVED");
        }).when(inventoryPort).reserve(any());
        orderService.recoverOrder(pending.orderNo());

        assertThat(orderService.getOrder(1L, pending.orderNo()).status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void recoveryLeaseFencesCompetingOrderRecoveryWorkers() {
        doThrow(new IllegalStateException("inventory unavailable")).when(inventoryPort).reserve(any());
        doThrow(new IllegalStateException("query unavailable")).when(inventoryPort).getReservation(anyString());
        OrderView pending = orderService.createOrder(command(1L, "idem-recovery-lease-001", 1));
        jdbcTemplate.update(
                "UPDATE trade_order SET next_recovery_at = ? WHERE order_no = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                pending.orderNo());

        assertThat(orderService.findRecoverableOrderNumbers(10)).contains(pending.orderNo());
        assertThat(orderService.tryClaimRecovery(pending.orderNo(), "worker-a")).isTrue();
        assertThat(orderService.tryClaimRecovery(pending.orderNo(), "worker-b")).isFalse();
        assertThat(orderService.findRecoverableOrderNumbers(10)).doesNotContain(pending.orderNo());
        assertThat(orderService.releaseRecoveryClaim(pending.orderNo(), "worker-b")).isFalse();
        assertThat(orderService.releaseRecoveryClaim(pending.orderNo(), "worker-a")).isTrue();
        assertThat(orderService.tryClaimRecovery(pending.orderNo(), "worker-b")).isTrue();

        jdbcTemplate.update(
                "UPDATE trade_order SET recovery_claim_until = ? WHERE order_no = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                pending.orderNo());
        assertThat(orderService.tryClaimRecovery(pending.orderNo(), "worker-c")).isTrue();
    }

    @Test
    void resolvesCommittedReservationAfterReserveResponseIsLost() {
        double recoveredBefore = resolutionCount("recovered");
        AtomicReference<InventoryPort.ReservationCommand> submitted = new AtomicReference<>();
        doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            throw new IllegalStateException("response lost after inventory commit");
        }).when(inventoryPort).reserve(any());
        when(inventoryPort.getReservation(anyString()))
                .thenAnswer(ignored -> reservation(submitted.get(), "RESERVED"));

        OrderView order = orderService.createOrder(command(1L, "idem-response-loss-001", 1));

        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT command FROM order_status_history
                WHERE order_id = (SELECT id FROM trade_order WHERE order_no = ?)
                  AND to_status = 'PENDING_PAYMENT'
                """, String.class, order.orderNo())).isEqualTo("RESOLVE_STOCK_RESULT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM order_status_history
                WHERE order_id = (SELECT id FROM trade_order WHERE order_no = ?)
                  AND to_status = 'PENDING_PAYMENT'
                """, String.class, order.orderNo())).isEqualTo("RESERVE_RESPONSE_UNKNOWN");
        assertThat(resolutionCount("recovered")).isEqualTo(recoveredBefore + 1.0d);
        verify(inventoryPort, times(1)).reserve(any());
        verify(inventoryPort, times(1)).getReservation(anyString());
    }

    @Test
    void doesNotAcceptAnUnrelatedReservationAsUnknownResultRecovery() {
        doThrow(new IllegalStateException("response lost after inventory commit"))
                .when(inventoryPort).reserve(any());
        when(inventoryPort.getReservation(anyString())).thenAnswer(invocation ->
                new InventoryPort.ReservationSnapshot(
                        invocation.getArgument(0),
                        "OTHER-ORDER",
                        "RESERVED",
                        10L,
                        java.time.Instant.now().plusSeconds(1200),
                        List.of(new InventoryPort.ReservationLine(101L, 1))));

        OrderView order = orderService.createOrder(command(1L, "idem-mismatched-response-001", 1));

        assertThat(order.status()).isEqualTo("PENDING_STOCK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recovery_attempts FROM trade_order WHERE order_no = ?",
                Integer.class,
                order.orderNo())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM trade_order WHERE order_no = ?",
                String.class,
                order.orderNo())).contains("result remains unknown");
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
    void observesTheWinningOrderWhenConcurrentPreflightCallsAreBulkheadRejected() throws Exception {
        CreateOrderCommand command = command(1L, "idem-concurrent-preflight-001", 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstAddressCall = new CountDownLatch(1);
        CountDownLatch releaseFirstAddressCall = new CountDownLatch(1);
        AtomicInteger addressCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (addressCalls.incrementAndGet() == 1) {
                firstAddressCall.countDown();
                if (!releaseFirstAddressCall.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the winning address call");
                }
                return address("Old Street 1");
            }
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE);
        }).when(addressPort).getAddress(1L, 501L);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return orderService.createOrder(command).orderNo();
                    })).toList();
            start.countDown();
            assertThat(firstAddressCall.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            releaseFirstAddressCall.countDown();

            Set<String> orderNumbers = new java.util.HashSet<>();
            for (Future<String> future : futures) {
                orderNumbers.add(future.get());
            }
            assertThat(orderNumbers).hasSize(1);
        } finally {
            releaseFirstAddressCall.countDown();
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
    void resolvesAnUnknownCreateResultByIdempotencyKeyWithoutRepeatingRemoteCalls() throws Exception {
        CreateOrderCommand command = command(1L, "idem-result-unknown-001", 1);
        OrderView created = orderService.createOrder(command);
        reset(catalogPort, inventoryPort, addressPort, marketingPort);

        OrderView retried = orderService.createOrder(command);

        assertThat(retried.orderNo()).isEqualTo(created.orderNo());
        verify(catalogPort, times(0)).getProduct(any());
        verify(inventoryPort, times(0)).getWarehouse(anyString());
        verify(addressPort, times(0)).getAddress(any(), any());
        verify(marketingPort, times(0)).lockPricing(any());

        mockMvc.perform(get("/api/v1/trade/orders/by-idempotency-key/idem-result-unknown-001")
                        .with(customerJwt("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(created.orderNo()))
                .andExpect(jsonPath("$.data.items[0].productId").isString())
                .andExpect(jsonPath("$.data.items[0].skuId").isString());

        mockMvc.perform(get("/api/v1/trade/orders/by-idempotency-key/idem-result-unknown-001")
                        .with(customerJwt("2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void orderListAndCancellationHttpEndpointsEnforceCustomerOwnership() throws Exception {
        OrderView created = orderService.createOrder(command(1L, "idem-http-owner-001", 1));

        mockMvc.perform(get("/api/v1/trade/orders/page")
                        .with(customerJwt("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value(created.orderNo()))
                .andExpect(jsonPath("$.data.items[0].items[0].productId").isString())
                .andExpect(jsonPath("$.data.items[0].items[0].skuId").isString());

        mockMvc.perform(get("/api/v1/trade/orders/page")
                        .with(customerJwt("2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(get("/api/v1/trade/orders")
                        .with(customerJwt("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value(created.orderNo()));

        mockMvc.perform(post("/api/v1/trade/orders/{orderNo}/cancel", created.orderNo())
                        .with(customerJwt("2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void orderCursorPaginationIsStableAndOwnerScoped() throws Exception {
        orderService.createOrder(command(1L, "idem-cursor-order-001", 1));
        orderService.createOrder(command(1L, "idem-cursor-order-002", 1));
        orderService.createOrder(command(1L, "idem-cursor-order-003", 1));

        JsonNode first = objectMapper.readTree(mockMvc.perform(get("/api/v1/trade/orders/cursor")
                        .with(customerJwt("1"))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isString())
                .andReturn().getResponse().getContentAsString());
        String cursor = first.at("/data/nextCursor").asText();

        JsonNode second = objectMapper.readTree(mockMvc.perform(get("/api/v1/trade/orders/cursor")
                        .with(customerJwt("1"))
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty())
                .andReturn().getResponse().getContentAsString());

        List<String> orderNumbers = new java.util.ArrayList<>();
        first.at("/data/items").forEach(item -> orderNumbers.add(item.get("orderNo").asText()));
        second.at("/data/items").forEach(item -> orderNumbers.add(item.get("orderNo").asText()));
        assertThat(orderNumbers).hasSize(3).doesNotHaveDuplicates();

        mockMvc.perform(get("/api/v1/trade/orders/cursor")
                        .with(customerJwt("2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mockMvc.perform(get("/api/v1/trade/orders/cursor")
                        .with(customerJwt("1"))
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void cancellationHttpEndpointReturnsCancelingWhenInventoryResultIsUnknown() throws Exception {
        OrderView created = orderService.createOrder(command(1L, "idem-http-canceling-001", 1));
        doThrow(new IllegalStateException("release response unavailable"))
                .when(inventoryPort).release(anyString());
        doThrow(new IllegalStateException("reservation query unavailable"))
                .when(inventoryPort).getReservation(anyString());

        mockMvc.perform(post("/api/v1/trade/orders/{orderNo}/cancel", created.orderNo())
                        .with(customerJwt("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(created.orderNo()))
                .andExpect(jsonPath("$.data.status").value("CANCELING"));

        mockMvc.perform(post("/api/v1/trade/orders/{orderNo}/cancel", created.orderNo())
                        .with(customerJwt("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_history WHERE order_id = "
                        + "(SELECT id FROM trade_order WHERE order_no = ?) "
                        + "AND to_status = 'CANCELING'",
                Integer.class,
                created.orderNo())).isEqualTo(1);
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
    void cancellationRejectsEveryMismatchedReservationIdentityBeforeCompleting() {
        OrderView created = orderService.createOrder(command(1L, "idem-cancel-mismatch-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo());
        InventoryPort.ReservationSnapshot correct = releasedReservation(reservationNo);
        AtomicReference<InventoryPort.ReservationSnapshot> response =
                new AtomicReference<>(correct);
        doAnswer(ignored -> response.get()).when(inventoryPort).release(anyString());

        List<InventoryPort.ReservationSnapshot> mismatches = List.of(
                new InventoryPort.ReservationSnapshot(
                        "RSV-OTHER", correct.orderNo(), correct.status(),
                        correct.warehouseId(), correct.expiresAt(), correct.items()),
                new InventoryPort.ReservationSnapshot(
                        correct.reservationNo(), "ORD-OTHER", correct.status(),
                        correct.warehouseId(), correct.expiresAt(), correct.items()),
                new InventoryPort.ReservationSnapshot(
                        correct.reservationNo(), correct.orderNo(), correct.status(),
                        99L, correct.expiresAt(), correct.items()),
                new InventoryPort.ReservationSnapshot(
                        correct.reservationNo(), correct.orderNo(), correct.status(),
                        correct.warehouseId(), correct.expiresAt(),
                        List.of(new InventoryPort.ReservationLine(999L, 1))));

        for (InventoryPort.ReservationSnapshot mismatch : mismatches) {
            response.set(mismatch);
            assertThat(orderService.cancelOrder(1L, created.orderNo()).status())
                    .isEqualTo("CANCELING");
        }

        response.set(correct);
        assertThat(orderService.cancelOrder(1L, created.orderNo()).status())
                .isEqualTo("CANCELED");
        verify(inventoryPort, times(5)).release(reservationNo);
    }

    @Test
    void appliesPaymentEventOnceAndPublishesOrderPaid() throws Exception {
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
        assertThatThrownBy(() -> orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                payment.eventId(),
                "PAY-CONFLICT",
                payment.orderNo(),
                payment.userId(),
                payment.reservationNo(),
                payment.amount())))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));

        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM consumed_event "
                        + "WHERE event_id = '00000000-0000-0000-0000-000000000101'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'", Integer.class)).isEqualTo(1);
        String outboxPayload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'OrderPaid'", String.class);
        JsonNode deliveryAddress = objectMapper.readTree(outboxPayload).at("/payload/deliveryAddress");
        assertThat(deliveryAddress.path("sourceAddressId").isIntegralNumber()).isTrue();
        assertThat(deliveryAddress.path("sourceAddressId").longValue()).isEqualTo(501L);
    }

    @Test
    void keepsPaymentConfirmingWithoutOrderPaidUntilInventoryAuthoritativelyConfirms() {
        OrderView created = orderService.createOrder(command(
                1L,
                "idem-payment-confirmation-recovery-001",
                1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo());
        PaymentSucceededCommand payment = new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000141",
                "PAY-CONFIRMING-141",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90"));

        when(inventoryPort.confirm(reservationNo))
                .thenThrow(new IllegalStateException("confirmation response unknown"))
                .thenReturn(storedReservation(reservationNo, "CONFIRMED"));
        when(inventoryPort.getReservation(reservationNo))
                .thenThrow(new IllegalStateException("authoritative query unavailable"));

        orderService.applyPaymentSucceeded(payment);

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PAYMENT_CONFIRMING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_id = ?",
                Integer.class,
                payment.eventId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT to_status FROM order_status_history "
                        + "WHERE order_id = (SELECT id FROM trade_order WHERE order_no = ?) "
                        + "ORDER BY created_at, id",
                String.class,
                created.orderNo())).containsSubsequence(
                        "PENDING_STOCK",
                        "PENDING_PAYMENT",
                        "PAYMENT_CONFIRMING");

        orderService.recoverOrder(created.orderNo());

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT to_status FROM order_status_history "
                        + "WHERE order_id = (SELECT id FROM trade_order WHERE order_no = ?) "
                        + "ORDER BY created_at, id",
                String.class,
                created.orderNo())).containsSubsequence(
                        "PAYMENT_CONFIRMING",
                        "PAID");
        verify(inventoryPort, times(2)).confirm(reservationNo);
    }

    @Test
    void bindsPaymentAggregateAcrossDistinctEventIdsAndRejectsAnotherPayment() {
        OrderView created = orderService.createOrder(command(1L, "idem-payment-aggregate-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        PaymentSucceededCommand payment = new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000102",
                "PAY-AGGREGATE-102",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90"));

        orderService.applyPaymentSucceeded(payment);
        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000103",
                "FulfillmentCreated",
                "FUL-AGGREGATE-102",
                created.orderNo(),
                1L));
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000104",
                payment.paymentNo(),
                payment.orderNo(),
                payment.userId(),
                payment.reservationNo(),
                payment.amount()));

        assertThatThrownBy(() -> orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000105",
                "PAY-OTHER-105",
                payment.orderNo(),
                payment.userId(),
                payment.reservationNo(),
                payment.amount())))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));

        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("FULFILLING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo())).isEqualTo(payment.paymentNo());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE consumer_group = ?",
                Integer.class,
                TradeOrderService.PAYMENT_CONSUMER_GROUP)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void advancesPaidOrderFromFulfillmentEventsExactlyOnce() throws Exception {
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
        assertThatThrownBy(() -> orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                fulfillmentCreated.eventId(),
                fulfillmentCreated.eventType(),
                "FUL-CONFLICT",
                fulfillmentCreated.orderNo(),
                fulfillmentCreated.userId())))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
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
                "SELECT COUNT(*) FROM consumed_event "
                        + "WHERE consumer_group = 'trade-fulfillment-events-v1' "
                        + "AND owner_user_id = 1",
                Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type IN ('OrderFulfilling','OrderShipped','OrderCompleted')",
                Integer.class)).isEqualTo(3);
        String completedPayload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'OrderCompleted'",
                String.class);
        JsonNode completedItem = objectMapper.readTree(completedPayload).at("/payload/items/0");
        assertThat(completedItem.path("lineNo").asInt()).isEqualTo(1);
        assertThat(completedItem.path("productId").asLong()).isEqualTo(1L);
        assertThat(completedItem.path("skuId").asLong()).isEqualTo(101L);
        assertThat(completedItem.path("productTitle").asText()).isEqualTo("Test Product");
        assertThat(completedItem.path("skuCode").asText()).isEqualTo("SKU-101");
        assertThat(completedItem.path("quantity").asLong()).isEqualTo(1L);
        assertThat(completedItem.path("lineAmount").decimalValue())
                .isEqualByComparingTo("19.90");
        assertThat(completedItem.path("discountAmount").decimalValue())
                .isEqualByComparingTo("0.00");
        assertThat(completedItem.path("payableAmount").decimalValue())
                .isEqualByComparingTo("19.90");
    }

    @Test
    void bindsFulfillmentAggregateAcrossTheWholeLifecycle() {
        OrderView created = orderService.createOrder(command(1L, "idem-fulfillment-aggregate-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?", String.class, created.orderNo());
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000115",
                "PAY-AGGREGATE-115",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90")));
        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000116",
                "FulfillmentCreated",
                "FUL-AGGREGATE-116",
                created.orderNo(),
                1L));

        assertThatThrownBy(() -> orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000117",
                "ShipmentDispatched",
                "FUL-OTHER-117",
                created.orderNo(),
                1L)))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("FULFILLING");

        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000118",
                "ShipmentDispatched",
                "FUL-AGGREGATE-116",
                created.orderNo(),
                1L));
        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000119",
                "FulfillmentCreated",
                "FUL-AGGREGATE-116",
                created.orderNo(),
                1L));

        assertThatThrownBy(() -> orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000120",
                "ShipmentSigned",
                "FUL-OTHER-120",
                created.orderNo(),
                1L)))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));

        orderService.applyFulfillmentEvent(new FulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000125",
                "ShipmentSigned",
                "FUL-AGGREGATE-116",
                created.orderNo(),
                1L));

        assertThat(orderService.getOrder(1L, created.orderNo()).status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fulfillment_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo())).isEqualTo("FUL-AGGREGATE-116");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE consumer_group = ?",
                Integer.class,
                TradeOrderService.FULFILLMENT_CONSUMER_GROUP)).isEqualTo(4);
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
    void doesNotPublishOrderPaidWhenTheReservationExpiredBeforePaymentConfirmation() {
        OrderView created = orderService.createOrder(command(
                1L, "idem-payment-expired-reservation-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo());
        when(inventoryPort.confirm(reservationNo))
                .thenReturn(storedReservation(reservationNo, "EXPIRED"));

        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000132",
                "PAY-EXPIRED-132",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90")));

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PAYMENT_EXCEPTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderPaid'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentReviewRequired'",
                Integer.class)).isEqualTo(1);
        verify(inventoryPort).confirm(reservationNo);
    }

    @Test
    void latePaymentDuringCancellationReleasesInventoryBeforeRequiringReview() {
        OrderView created = orderService.createOrder(command(
                1L, "idem-late-payment-canceling-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo());
        doThrow(new IllegalStateException("release unavailable"))
                .when(inventoryPort).release(reservationNo);
        when(inventoryPort.getReservation(reservationNo))
                .thenThrow(new IllegalStateException("query unavailable"));

        OrderView canceling = orderService.cancelOrder(1L, created.orderNo());
        assertThat(canceling.status()).isEqualTo("CANCELING");

        org.mockito.Mockito.doReturn(storedReservation(reservationNo, "RELEASED"))
                .when(inventoryPort).release(reservationNo);
        org.mockito.Mockito.doReturn(storedReservation(reservationNo, "RELEASED"))
                .when(inventoryPort).getReservation(reservationNo);
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000133",
                "PAY-CANCELING-133",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90")));

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PAYMENT_EXCEPTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderCanceled'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentReviewRequired'",
                Integer.class)).isEqualTo(1);
        verify(inventoryPort, times(2)).release(reservationNo);
    }

    @Test
    void exceptionalPaymentRefundFailureRemainsReviewableAndSuccessClosesExactlyOnce() {
        OrderView created = orderService.createOrder(command(
                1L, "idem-payment-exception-refund-001", 1));
        String reservationNo = jdbcTemplate.queryForObject(
                "SELECT reservation_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo());
        when(inventoryPort.confirm(reservationNo))
                .thenReturn(storedReservation(reservationNo, "EXPIRED"));
        orderService.applyPaymentSucceeded(new PaymentSucceededCommand(
                "00000000-0000-0000-0000-000000000134",
                "PAY-EXCEPTION-134",
                created.orderNo(),
                1L,
                reservationNo,
                new BigDecimal("19.90")));

        RefundEventCommand failed = new RefundEventCommand(
                "00000000-0000-0000-0000-000000000135",
                "RefundFailed",
                "RF-EXCEPTION-135",
                "PEX-" + created.orderNo(),
                created.orderNo(),
                "PAY-EXCEPTION-134",
                1L,
                new BigDecimal("19.90"));
        orderService.applyPaymentExceptionRefundEvent(failed);
        orderService.applyPaymentExceptionRefundEvent(failed);

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("PAYMENT_EXCEPTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT exception_refund_no FROM trade_order WHERE order_no = ?",
                String.class,
                created.orderNo())).isEqualTo("RF-EXCEPTION-135");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_history "
                        + "WHERE order_id = (SELECT id FROM trade_order WHERE order_no = ?) "
                        + "AND command = 'PAYMENT_EXCEPTION_REFUND_FAILED'",
                Integer.class,
                created.orderNo())).isEqualTo(1);

        RefundEventCommand succeeded = new RefundEventCommand(
                "00000000-0000-0000-0000-000000000136",
                "RefundSucceeded",
                failed.refundNo(),
                failed.afterSaleNo(),
                failed.orderNo(),
                failed.paymentNo(),
                failed.userId(),
                failed.amount());
        orderService.applyPaymentExceptionRefundEvent(succeeded);
        orderService.applyPaymentExceptionRefundEvent(succeeded);

        assertThat(orderService.getOrder(1L, created.orderNo()).status())
                .isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'OrderClosed'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> orderService.applyPaymentExceptionRefundEvent(
                new RefundEventCommand(
                        "00000000-0000-0000-0000-000000000137",
                        "RefundSucceeded",
                        "RF-CONFLICT-137",
                        failed.afterSaleNo(),
                        failed.orderNo(),
                        failed.paymentNo(),
                        failed.userId(),
                        failed.amount())))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void exposesPaymentContextOnlyToTheTrustedPaymentService() throws Exception {
        OrderView created = orderService.createOrder(command(1L, "idem-context-001", 1));
        String path = "/api/v1/trade/internal/orders/" + created.orderNo() + "/payment-context";

        mockMvc.perform(get(path).with(customerJwt("1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path)
                        .header("X-Internal-Service", "payment-service")
                        .header("X-Internal-Token",
                                "test-trade-internal-token-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path)
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token",
                                "test-payment-internal-token-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path)
                        .header("X-Internal-Service", "payment-service")
                        .header("X-Internal-Token",
                                "test-payment-internal-token-with-at-least-32-characters"))
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
                .andExpect(jsonPath("$.service").value("trade-service"));
        mockMvc.perform(get("/actuator/businessprocesses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/businessprocesses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/businessprocesses")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("trade-service"));
    }

    @Test
    void locatesAgingBusinessProcessesWithoutExposingCustomerData() throws Exception {
        OrderView created = orderService.createOrder(command(1L, "idem-process-observability", 1));
        jdbcTemplate.update("""
                UPDATE trade_order
                SET status = 'CANCELING', last_error = 'inventory release pending',
                    updated_at = DATEADD('MINUTE', -5, CURRENT_TIMESTAMP)
                WHERE order_no = ?
                """, created.orderNo());

        mockMvc.perform(get("/actuator/businessprocesses?limit=500")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states[2].domain").value("ORDER"))
                .andExpect(jsonPath("$.states[2].status").value("CANCELING"))
                .andExpect(jsonPath("$.states[2].count").value(1))
                .andExpect(jsonPath("$.states[2].oldestAgeSeconds").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(299.0)))
                .andExpect(jsonPath("$.activeProcesses.length()").value(1))
                .andExpect(jsonPath("$.activeProcesses[0].referenceNo").value(created.orderNo()))
                .andExpect(jsonPath("$.activeProcesses[0].lastError").value("inventory release pending"))
                .andExpect(jsonPath("$.activeProcesses[0].userId").doesNotExist());
    }

    @Test
    void retriesTradeOutboxPublicationWithoutLosingEvents() throws Exception {
        orderService.createOrder(command(1L, "idem-outbox-001", 1));
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        when(publisher.publishAsync(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")),
                        java.util.concurrent.CompletableFuture.completedFuture(null));
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-order-events",
                "ecommerce-flash-sale-events", 2000,
                Duration.ofSeconds(1), 100, 1, "trade-flow-test", Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        MutableClock retryClock = new MutableClock(
                Instant.ofEpochMilli(System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxPublisherJob job = new OutboxPublisherJob(
                outboxMapper,
                outboxClaimService,
                publisher,
                properties,
                new com.ecommerce.trade.infrastructure.messaging.ProcessTerminationFaultInjector(
                        com.ecommerce.trade.infrastructure.messaging.ProcessTerminationFaultProperties.disabled()),
                retryClock,
                meterRegistry,
                shardRouter);

        try {
            Instant databaseBeforeFailure = outboxMapper.currentTime();
            job.publishPendingEvents();
            Instant databaseAfterFailure = outboxMapper.currentTime();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'", Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isZero();
            Map<String, Object> failedState = jdbcTemplate.queryForMap("""
                    SELECT status, attempts, next_attempt_at, claim_owner, claim_until, last_error
                    FROM outbox_event
                    WHERE attempts = 1
                    """);
            assertThat(failedState.get("status")).isEqualTo("PENDING");
            assertThat(((Number) failedState.get("attempts")).intValue()).isEqualTo(1);
            assertThat(((java.sql.Timestamp) failedState.get("next_attempt_at")).toInstant())
                    .isBetween(
                            databaseBeforeFailure.plusSeconds(1),
                            databaseAfterFailure.plusSeconds(1));
            assertThat(failedState.get("claim_owner")).isNull();
            assertThat(failedState.get("claim_until")).isNull();
            assertThat(failedState.get("last_error").toString()).contains("broker unavailable");
            assertThat(meterRegistry.get("ecommerce.outbox.publications")
                    .tag("outcome", "failure").counter().count()).isEqualTo(1);
            assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isEqualTo(2);

            when(publisher.publishAsync(anyString(), anyString(), anyString()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            job.publishPendingEvents();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isZero();

            jdbcTemplate.update(
                    "UPDATE outbox_event SET next_attempt_at = ? WHERE attempts = 1",
                    java.sql.Timestamp.from(outboxMapper.currentTime().minusMillis(1)));
            job.publishPendingEvents();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'", Integer.class)).isEqualTo(1);

            job.publishPendingEvents();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'", Integer.class)).isEqualTo(2);
            assertThat(meterRegistry.get("ecommerce.outbox.publications")
                    .tag("outcome", "success").counter().count()).isEqualTo(2);
            assertThat(meterRegistry.get("ecommerce.outbox.pending").gauge().value()).isZero();
        } finally {
            job.close();
        }
    }

    private CreateOrderCommand command(Long userId, String key, long quantity) {
        return new CreateOrderCommand(userId, key, 501L,
                List.of(new OrderLineCommand(1L, 101L, quantity)), List.of());
    }

    private InventoryPort.ReservationSnapshot reservation(
            InventoryPort.ReservationCommand command,
            String status) {
        return new InventoryPort.ReservationSnapshot(
                command.reservationNo(),
                command.orderNo(),
                status,
                command.warehouseId(),
                command.expiresAt(),
                command.items());
    }

    private InventoryPort.ReservationSnapshot releasedReservation(String reservationNo) {
        return storedReservation(reservationNo, "RELEASED");
    }

    private InventoryPort.ReservationSnapshot storedReservation(
            String reservationNo,
            String status) {
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM trade_order WHERE reservation_no = ?",
                Long.class,
                reservationNo);
        String orderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM trade_order WHERE id = ?",
                String.class,
                orderId);
        Long warehouseId = jdbcTemplate.queryForObject(
                "SELECT warehouse_id FROM trade_order WHERE id = ?",
                Long.class,
                orderId);
        List<InventoryPort.ReservationLine> items = jdbcTemplate.query(
                "SELECT sku_id, quantity FROM order_item WHERE order_id = ? ORDER BY line_no",
                (resultSet, rowNumber) -> new InventoryPort.ReservationLine(
                        resultSet.getLong("sku_id"),
                        resultSet.getLong("quantity")),
                orderId);
        return new InventoryPort.ReservationSnapshot(
                reservationNo,
                orderNo,
                status,
                warehouseId,
                java.time.Instant.EPOCH,
                items);
    }

    private double resolutionCount(String outcome) {
        return meterRegistry.get("ecommerce.trade.inventory.reservation.unknown.result.resolutions")
                .tag("service", "trade-service")
                .tag("dependency", "inventory-service")
                .tag("operation", "reserve")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return zone.equals(newZone) ? this : new MutableClock(current, newZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }
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
