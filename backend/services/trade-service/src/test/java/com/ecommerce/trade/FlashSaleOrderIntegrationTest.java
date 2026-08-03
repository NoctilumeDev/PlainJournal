package com.ecommerce.trade;

import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.FlashSaleAdmissionAcceptedCommand;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.application.service.FlashSaleOrderService;
import com.ecommerce.trade.infrastructure.id.DistributedIdWorkerLeaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
class FlashSaleOrderIntegrationTest {

    @MockitoBean
    private CatalogPort catalogPort;

    @MockitoBean
    private InventoryPort inventoryPort;

    @MockitoBean
    private AddressPort addressPort;

    @MockitoBean
    private MarketingPort marketingPort;

    private final FlashSaleOrderService flashSaleOrderService;
    private final JdbcTemplate jdbcTemplate;
    private final DistributedIdWorkerLeaseManager distributedIdWorkerLeaseManager;

    @Autowired
    FlashSaleOrderIntegrationTest(
            FlashSaleOrderService flashSaleOrderService,
            JdbcTemplate jdbcTemplate,
            DistributedIdWorkerLeaseManager distributedIdWorkerLeaseManager) {
        this.flashSaleOrderService = flashSaleOrderService;
        this.jdbcTemplate = jdbcTemplate;
        this.distributedIdWorkerLeaseManager = distributedIdWorkerLeaseManager;
    }

    @BeforeEach
    void setUp() {
        clean();
        reset(catalogPort, inventoryPort, addressPort, marketingPort);
        when(addressPort.getAddress(101L, 501L)).thenReturn(address());
        when(catalogPort.getProduct(1L)).thenReturn(product());
        when(inventoryPort.getWarehouse("PRIMARY"))
                .thenReturn(new InventoryPort.WarehouseSnapshot(10L, "PRIMARY", "ACTIVE"));
        doAnswer(invocation -> reservation(invocation.getArgument(0), "RESERVED"))
                .when(inventoryPort).reserve(any());
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM flash_sale_order_request");
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM order_status_history");
        jdbcTemplate.update("DELETE FROM order_address_snapshot");
        jdbcTemplate.update("DELETE FROM order_discount_allocation");
        jdbcTemplate.update("DELETE FROM order_price_snapshot");
        jdbcTemplate.update("DELETE FROM order_benefit_selection");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM trade_order");
    }

    @Test
    void duplicateAdmissionCreatesOneOrderWithTheActivityPriceAndSuccessResult() {
        FlashSaleAdmissionAcceptedCommand command = command("FST-success");

        flashSaleOrderService.handle(command);
        flashSaleOrderService.handle(command);
        FlashSaleAdmissionAcceptedCommand conflict = new FlashSaleAdmissionAcceptedCommand(
                command.eventId(),
                command.requestToken(),
                command.activityNo(),
                command.userId(),
                command.addressId(),
                command.productId(),
                command.skuId(),
                new BigDecimal("10.90"),
                command.acceptedAt(),
                command.activityEndsAt());
        assertThatThrownBy(() -> flashSaleOrderService.handle(conflict))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));

        assertThat(count("trade_order")).isOne();
        assertThat(count("flash_sale_order_request")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM consumed_event WHERE event_id = ?",
                Long.class,
                command.eventId())).isEqualTo(command.userId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("ORDER_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_source FROM trade_order WHERE source_reference = ?",
                String.class,
                command.requestToken())).isEqualTo("FLASH_SALE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM order_item",
                BigDecimal.class)).isEqualByComparingTo("9.90");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payable_amount FROM order_price_snapshot",
                BigDecimal.class)).isEqualByComparingTo("9.90");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pricing_version FROM order_price_snapshot",
                String.class)).isEqualTo("flash-sale-v1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type = 'FlashSaleOrderSucceeded' "
                        + "AND destination_topic = 'ecommerce-flash-sale-events'",
                Integer.class)).isOne();
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM trade_order WHERE source_reference = ?",
                Long.class,
                command.requestToken());
        assertThat(DistributedIdGenerator.workerIdOf(orderId))
                .isEqualTo(distributedIdWorkerLeaseManager.workerId());
        verify(marketingPort, never()).lockPricing(any());
    }

    @Test
    void recoveryCannotDuplicateAnInFlightFlashSaleInventoryCommand() throws Exception {
        FlashSaleAdmissionAcceptedCommand command = command("FST-inflight-recovery");
        CountDownLatch inventoryEntered = new CountDownLatch(1);
        CountDownLatch allowInventoryResponse = new CountDownLatch(1);
        doAnswer(invocation -> {
            inventoryEntered.countDown();
            if (!allowInventoryResponse.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test inventory response was not released");
            }
            return reservation(invocation.getArgument(0), "RESERVED");
        }).when(inventoryPort).reserve(any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> primary = executor.submit(() -> flashSaleOrderService.handle(command));
            assertThat(inventoryEntered.await(10, TimeUnit.SECONDS)).isTrue();

            flashSaleOrderService.recover(command.requestToken());
            allowInventoryResponse.countDown();
            primary.get(10, TimeUnit.SECONDS);
        } finally {
            allowInventoryResponse.countDown();
            executor.shutdownNow();
        }

        verify(inventoryPort, times(1)).reserve(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("ORDER_CREATED");
    }

    @Test
    void acceptsTrailingZeroScaleButRejectsAnActualSubCentPrice() {
        flashSaleOrderService.handle(command(
                "FST-trailing-zero-price",
                new BigDecimal("9.900")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT unit_price FROM order_item",
                BigDecimal.class)).isEqualByComparingTo("9.90");
        assertThatThrownBy(() -> flashSaleOrderService.handle(command(
                "FST-sub-cent-price",
                new BigDecimal("9.901"))))
                .isInstanceOfSatisfying(
                        TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        assertThat(count("trade_order")).isOne();
    }

    @Test
    void rejectedInventoryClosesTheOrderAndPublishesAFailureResult() {
        doAnswer(invocation -> reservation(invocation.getArgument(0), "REJECTED"))
                .when(inventoryPort).reserve(any());
        FlashSaleAdmissionAcceptedCommand command = command("FST-rejected");

        flashSaleOrderService.handle(command);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE source_reference = ?",
                String.class,
                command.requestToken())).isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("OUT_OF_STOCK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type = 'FlashSaleOrderFailed' "
                        + "AND destination_topic = 'ecommerce-flash-sale-events'",
                Integer.class)).isOne();
        verify(marketingPort, never()).lockPricing(any());
    }

    @Test
    void unknownInventoryResultRemainsProcessingAndRecoveryConverges() {
        doThrow(new IllegalStateException("inventory reserve timeout"))
                .when(inventoryPort).reserve(any());
        doThrow(new IllegalStateException("inventory query timeout"))
                .when(inventoryPort).getReservation(any());
        FlashSaleAdmissionAcceptedCommand command = command("FST-recovery");

        flashSaleOrderService.handle(command);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE source_reference = ?",
                String.class,
                command.requestToken())).isEqualTo("PENDING_STOCK");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM flash_sale_order_request WHERE request_token = ?",
                Integer.class,
                command.requestToken())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type IN ('FlashSaleOrderSucceeded', 'FlashSaleOrderFailed')",
                Integer.class)).isZero();

        doAnswer(invocation -> reservation(invocation.getArgument(0), "RESERVED"))
                .when(inventoryPort).reserve(any());
        flashSaleOrderService.recover(command.requestToken());

        assertThat(count("trade_order")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE source_reference = ?",
                String.class,
                command.requestToken())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_order_request WHERE request_token = ?",
                String.class,
                command.requestToken())).isEqualTo("ORDER_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE event_type = 'FlashSaleOrderSucceeded' "
                        + "AND destination_topic = 'ecommerce-flash-sale-events'",
                Integer.class)).isOne();
    }

    private FlashSaleAdmissionAcceptedCommand command(String requestToken) {
        return command(requestToken, new BigDecimal("9.90"));
    }

    private FlashSaleAdmissionAcceptedCommand command(
            String requestToken,
            BigDecimal salePrice) {
        Instant acceptedAt = Instant.now().minusSeconds(1);
        return new FlashSaleAdmissionAcceptedCommand(
                UUID.randomUUID().toString(),
                requestToken,
                "FSA-M6",
                101L,
                501L,
                1L,
                101L,
                salePrice,
                acceptedAt,
                acceptedAt.plusSeconds(600));
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

    private AddressPort.AddressSnapshot address() {
        return new AddressPort.AddressSnapshot(
                501L,
                "Flash Customer",
                "+86 13800000000",
                "Zhejiang",
                "330000",
                "Hangzhou",
                "330100",
                "Xihu",
                "330106",
                "M6 Test Street 1",
                "310000");
    }

    private CatalogPort.ProductSnapshot product() {
        return new CatalogPort.ProductSnapshot(
                1L,
                "Catalog Product",
                "ACTIVE",
                List.of(new CatalogPort.SkuSnapshot(
                        101L,
                        "SKU-101",
                        "Default",
                        "{\"size\":\"M\"}",
                        new BigDecimal("19.90"),
                        "ACTIVE")),
                List.of(new CatalogPort.MediaSnapshot(101L, "products/m6.png", 0)));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
