package com.ecommerce.fulfillment;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedItem;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ReturnReceiptView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.SubmitReturnShipmentCommand;
import com.ecommerce.fulfillment.application.service.ReturnReceiptService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class ReturnReceiptFlowIntegrationTest {

    private static final long USER_ID = 1001L;

    private final ReturnReceiptService returnReceiptService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    ReturnReceiptFlowIntegrationTest(
            ReturnReceiptService returnReceiptService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper) {
        this.returnReceiptService = returnReceiptService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM consumed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM return_status_history");
        jdbcTemplate.update("DELETE FROM return_item");
        jdbcTemplate.update("DELETE FROM return_receipt");
    }

    @Test
    void createsOneImmutableReturnReceiptForDuplicateApprovalEvents() {
        AfterSaleApprovedCommand command = approvedCommand();

        ReturnReceiptView first = returnReceiptService.createFromAfterSaleApproved(command);
        ReturnReceiptView repeated = returnReceiptService.createFromAfterSaleApproved(command);

        assertThat(repeated.returnReceiptNo()).isEqualTo(first.returnReceiptNo());
        assertThat(first.status()).isEqualTo("WAIT_SHIPMENT");
        assertThat(first.refundAmount()).isEqualByComparingTo("35.00");
        assertThat(first.items()).hasSize(2);
        assertThat(first.items()).extracting(item -> item.refundableAmount().toPlainString())
                .containsExactly("18.00", "17.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM return_receipt", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM return_item", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void movesFromCustomerShipmentToWarehouseInspectionAndEmitsOneInventoryEvent() {
        ReturnReceiptView created = returnReceiptService.createFromAfterSaleApproved(approvedCommand());

        ReturnReceiptView returning = returnReceiptService.submitShipment(
                USER_ID, created.returnReceiptNo(),
                new SubmitReturnShipmentCommand("MOCK_EXPRESS", "RETURN-TRACK-001"));
        ReturnReceiptView repeatedShipment = returnReceiptService.submitShipment(
                USER_ID, created.returnReceiptNo(),
                new SubmitReturnShipmentCommand("MOCK_EXPRESS", "RETURN-TRACK-001"));
        assertThat(repeatedShipment.status()).isEqualTo(returning.status()).isEqualTo("RETURNING");

        ReturnReceiptView received = returnReceiptService.receive(created.returnReceiptNo(), "warehouse-1");
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(returnReceiptService.receive(created.returnReceiptNo(), "warehouse-1").status())
                .isEqualTo("RECEIVED");

        ReturnReceiptView inspected = returnReceiptService.inspect(
                created.returnReceiptNo(), "All returned goods accepted", "warehouse-1");
        assertThat(inspected.status()).isEqualTo("INSPECTED");
        assertThat(inspected.inspectedAt()).isNotNull();
        assertThat(returnReceiptService.inspect(
                created.returnReceiptNo(), "All returned goods accepted", "warehouse-1").status())
                .isEqualTo("INSPECTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM return_status_history", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ReturnInspected'", Integer.class))
                .isEqualTo(1);
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'ReturnInspected'", String.class);
        assertThat(payload).contains("RES-AFTER-SALE-001", "\"skuId\":101", "\"quantity\":1");
    }

    @Test
    void separatesCustomerOwnershipFromWarehouseOperations() throws Exception {
        ReturnReceiptView created = returnReceiptService.createFromAfterSaleApproved(approvedCommand());

        mockMvc.perform(get("/api/v1/fulfillment/returns/{returnReceiptNo}", created.returnReceiptNo())
                        .with(customerJwt(1002L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/fulfillment/admin/returns/{returnReceiptNo}/receive",
                        created.returnReceiptNo()).with(customerJwt(USER_ID)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/fulfillment/returns/{returnReceiptNo}/shipment",
                        created.returnReceiptNo())
                        .with(customerJwt(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShipmentRequest("MOCK_EXPRESS", "RETURN-TRACK-API"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURNING"));
        mockMvc.perform(post("/api/v1/fulfillment/admin/returns/{returnReceiptNo}/receive",
                        created.returnReceiptNo()).with(warehouseJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
        mockMvc.perform(post("/api/v1/fulfillment/admin/returns/{returnReceiptNo}/inspect",
                        created.returnReceiptNo())
                        .with(warehouseJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InspectRequest("Accepted"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSPECTED"));
    }

    private AfterSaleApprovedCommand approvedCommand() {
        return new AfterSaleApprovedCommand(
                "00000000-0000-0000-0000-000000000401",
                "AS-401", "ORDER-401", USER_ID, 10L, "RES-AFTER-SALE-001",
                new BigDecimal("35.00"),
                List.of(
                        new AfterSaleApprovedItem(1, 101L, 1, new BigDecimal("18.00")),
                        new AfterSaleApprovedItem(2, 102L, 1, new BigDecimal("17.00"))));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt(long userId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor warehouseJwt() {
        return jwt().jwt(token -> token.subject("warehouse-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE"));
    }

    private record ShipmentRequest(String carrier, String trackingNo) {
    }

    private record InspectRequest(String remark) {
    }
}
