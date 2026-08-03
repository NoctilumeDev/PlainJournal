package com.ecommerce.trade;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleFulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleView;
import com.ecommerce.trade.application.model.TradeModels.ApplyAfterSaleCommand;
import com.ecommerce.trade.application.model.TradeModels.RefundEventCommand;
import com.ecommerce.trade.application.model.TradeModels.ReturnStockedCommand;
import com.ecommerce.trade.application.model.TradeModels.ReviewAfterSaleCommand;
import com.ecommerce.trade.application.service.AfterSaleService;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.application.port.MarketingPort;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class AfterSaleFlowIntegrationTest {

    private static final long USER_ID = 1001L;
    private static final long ORDER_ID = 9001L;
    private static final String ORDER_NO = "ORD-AFTER-SALE-001";
    private static final String PAYMENT_NO = "PAY-AFTER-SALE-001";

    @MockitoBean
    private CatalogPort catalogPort;

    @MockitoBean
    private InventoryPort inventoryPort;

    @MockitoBean
    private AddressPort addressPort;

    @MockitoBean
    private MarketingPort marketingPort;

    private final AfterSaleService afterSaleService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    AfterSaleFlowIntegrationTest(
            AfterSaleService afterSaleService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper) {
        this.afterSaleService = afterSaleService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void seedCompletedOrderWithPriceAllocations() {
        Instant now = Instant.parse("2026-07-16T08:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO trade_order
                    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
                     warehouse_code, warehouse_id, payment_no, status, original_amount, discount_amount,
                     total_amount, marketing_lock_no, payment_deadline, recovery_attempts,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ORDER_ID, ORDER_NO, USER_ID, "idem-seed-order", "0".repeat(64), "RES-AFTER-SALE-001",
                "PRIMARY", 10L, PAYMENT_NO, "COMPLETED", new BigDecimal("40.00"), new BigDecimal("5.00"),
                new BigDecimal("35.00"), "MKT-AFTER-SALE-001", now.plusSeconds(900), 0,
                0, now, now);
        insertOrderItem(9101L, 1, 101L, "Air Conditioner", "Standard", "20.00", "2.00", "18.00", now);
        insertOrderItem(9102L, 2, 102L, "Air Filter", "White", "20.00", "3.00", "17.00", now);
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM after_sale_history");
        jdbcTemplate.update("DELETE FROM after_sale_item");
        jdbcTemplate.update("DELETE FROM after_sale_order");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM trade_order");
    }

    @Test
    void snapshotsTheOriginalOrderAllocationAndEnforcesOneWholeAfterSalePerOrder() {
        ApplyAfterSaleCommand command = new ApplyAfterSaleCommand(
                USER_ID, "idem-after-sale-001", ORDER_NO, "The whole order is no longer needed");

        AfterSaleView created = afterSaleService.apply(command);
        AfterSaleView repeated = afterSaleService.apply(command);

        assertThat(repeated.afterSaleNo()).isEqualTo(created.afterSaleNo());
        assertThat(created.status()).isEqualTo("APPLIED");
        assertThat(created.refundAmount()).isEqualByComparingTo("35.00");
        assertThat(created.items()).hasSize(2);
        assertThat(created.items()).extracting(item -> item.refundableAmount().toPlainString())
                .containsExactly("18.00", "17.00");
        assertThat(created.items()).extracting(item -> item.discountAmount().toPlainString())
                .containsExactly("2.00", "3.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM after_sale_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM after_sale_item", Integer.class))
                .isEqualTo(2);

        assertThatThrownBy(() -> afterSaleService.apply(new ApplyAfterSaleCommand(
                USER_ID, "idem-after-sale-002", ORDER_NO, "A second request")))
                .isInstanceOf(TradeException.class)
                .satisfies(exception -> assertThat(((TradeException) exception).error())
                        .isEqualTo(TradeError.AFTER_SALE_ALREADY_EXISTS));
        assertThatThrownBy(() -> afterSaleService.apply(new ApplyAfterSaleCommand(
                USER_ID, "idem-after-sale-001", ORDER_NO, "Changed request")))
                .isInstanceOf(TradeException.class)
                .satisfies(exception -> assertThat(((TradeException) exception).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void rejectsAnAfterSaleApplicationOutsideTheConfiguredWindow() {
        jdbcTemplate.update("UPDATE trade_order SET updated_at = ? WHERE id = ?",
                Instant.now().minusSeconds(31L * 24 * 60 * 60), ORDER_ID);

        assertThatThrownBy(() -> afterSaleService.apply(new ApplyAfterSaleCommand(
                USER_ID, "idem-after-sale-expired", ORDER_NO, "Too late")))
                .isInstanceOf(TradeException.class)
                .satisfies(exception -> assertThat(((TradeException) exception).error())
                        .isEqualTo(TradeError.AFTER_SALE_WINDOW_EXPIRED));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM after_sale_order", Integer.class))
                .isZero();
    }

    @Test
    void completesTheTradeSideOfTheReturnRefundFlowExactlyOnce() {
        AfterSaleView applied = afterSaleService.apply(new ApplyAfterSaleCommand(
                USER_ID, "idem-after-sale-flow", ORDER_NO, "Return everything"));
        String afterSaleNo = applied.afterSaleNo();

        afterSaleService.review(new ReviewAfterSaleCommand(afterSaleNo, true, "Approved", "admin-1"));
        AfterSaleFulfillmentEventCommand submitted = new AfterSaleFulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000201", "ReturnShipmentSubmitted",
                afterSaleNo, "RET-001", ORDER_NO, USER_ID);
        afterSaleService.applyFulfillmentEvent(submitted);
        afterSaleService.applyFulfillmentEvent(submitted);
        assertThatThrownBy(() -> afterSaleService.applyFulfillmentEvent(
                new AfterSaleFulfillmentEventCommand(
                        submitted.eventId(),
                        submitted.eventType(),
                        submitted.afterSaleNo(),
                        "RET-CONFLICT",
                        submitted.orderNo(),
                        submitted.userId())))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        afterSaleService.applyFulfillmentEvent(new AfterSaleFulfillmentEventCommand(
                "00000000-0000-0000-0000-000000000202", "ReturnReceived",
                afterSaleNo, "RET-001", ORDER_NO, USER_ID));

        ReturnStockedCommand stocked = new ReturnStockedCommand(
                "00000000-0000-0000-0000-000000000203", afterSaleNo, "RET-001",
                ORDER_NO, USER_ID, 10L);
        afterSaleService.applyReturnStocked(stocked);
        afterSaleService.applyReturnStocked(stocked);
        assertThatThrownBy(() -> afterSaleService.applyReturnStocked(new ReturnStockedCommand(
                stocked.eventId(),
                stocked.afterSaleNo(),
                stocked.returnReceiptNo(),
                stocked.orderNo(),
                stocked.userId(),
                11L)))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        assertThat(afterSaleService.get(afterSaleNo).status()).isEqualTo("REFUNDING");

        RefundEventCommand failed = new RefundEventCommand(
                "00000000-0000-0000-0000-000000000204", "RefundFailed", "RF-001",
                afterSaleNo, ORDER_NO, PAYMENT_NO, USER_ID, new BigDecimal("35.00"));
        afterSaleService.applyRefundEvent(failed);
        assertThatThrownBy(() -> afterSaleService.applyRefundEvent(new RefundEventCommand(
                failed.eventId(),
                failed.eventType(),
                failed.refundNo(),
                failed.afterSaleNo(),
                failed.orderNo(),
                failed.paymentNo(),
                failed.userId(),
                new BigDecimal("34.00"))))
                .isInstanceOf(TradeException.class)
                .satisfies(error -> assertThat(((TradeException) error).error())
                        .isEqualTo(TradeError.IDEMPOTENCY_CONFLICT));
        assertThat(afterSaleService.get(afterSaleNo).status()).isEqualTo("REFUND_FAILED");

        afterSaleService.applyRefundEvent(new RefundEventCommand(
                "00000000-0000-0000-0000-000000000205", "RefundSucceeded", "RF-001",
                afterSaleNo, ORDER_NO, PAYMENT_NO, USER_ID, new BigDecimal("35.00")));
        AfterSaleView completed = afterSaleService.get(afterSaleNo);

        afterSaleService.applyRefundEvent(new RefundEventCommand(
                "00000000-0000-0000-0000-000000000206", "RefundFailed", "RF-001",
                afterSaleNo, ORDER_NO, PAYMENT_NO, USER_ID, new BigDecimal("35.00")));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.refundNo()).isEqualTo("RF-001");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE owner_user_id = ?",
                Integer.class,
                USER_ID)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM after_sale_history", Integer.class))
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefundRequested'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exposesCustomerAndAdminEndpointsWithRoleIsolation() throws Exception {
        String request = objectMapper.writeValueAsString(new ApplyRequest("Return the complete order"));
        String response = mockMvc.perform(post("/api/v1/trade/orders/{orderNo}/after-sales", ORDER_NO)
                        .with(customerJwt())
                        .header("Idempotency-Key", "idem-after-sale-api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.refundAmount").value(35.00))
                .andExpect(jsonPath("$.data.userId").isString())
                .andExpect(jsonPath("$.data.items[0].skuId").isString())
                .andReturn().getResponse().getContentAsString();
        String afterSaleNo = objectMapper.readTree(response).path("data").path("afterSaleNo").asText();

        mockMvc.perform(get("/api/v1/trade/admin/after-sales/{afterSaleNo}", afterSaleNo)
                        .with(customerJwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/trade/admin/after-sales/{afterSaleNo}/review", afterSaleNo)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewRequest(true, "Approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAIT_RETURN"));
        mockMvc.perform(get("/api/v1/trade/after-sales/{afterSaleNo}", afterSaleNo)
                        .with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    private void insertOrderItem(
            long id,
            int lineNo,
            long skuId,
            String productTitle,
            String skuName,
            String lineAmount,
            String discountAmount,
            String payableAmount,
            Instant now) {
        jdbcTemplate.update("""
                INSERT INTO order_item
                    (id, order_id, line_no, product_id, sku_id, product_title, sku_code,
                     sku_name, spec_json, unit_price, quantity, line_amount, discount_amount,
                     payable_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, ORDER_ID, lineNo, 100L + lineNo, skuId, productTitle, "SKU-" + skuId,
                skuName, "{}", new BigDecimal(lineAmount), 1L, new BigDecimal(lineAmount),
                new BigDecimal(discountAmount), new BigDecimal(payableAmount), now);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt().jwt(token -> token.subject(Long.toString(USER_ID)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private record ApplyRequest(String reason) {
    }

    private record ReviewRequest(boolean approved, String reason) {
    }
}
