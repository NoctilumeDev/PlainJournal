package com.ecommerce.marketing;

import com.ecommerce.marketing.application.model.FlashSaleModels.CreateFlashSaleCommand;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleActivityView;
import com.ecommerce.marketing.application.service.FlashSaleService;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class FlashSaleUnavailableIntegrationTest {

    private final FlashSaleService flashSaleService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @Autowired
    FlashSaleUnavailableIntegrationTest(
            FlashSaleService flashSaleService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.flashSaleService = flashSaleService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM flash_sale_outbox_event");
        jdbcTemplate.update("DELETE FROM flash_sale_admission");
        jdbcTemplate.update("DELETE FROM flash_sale_activity");
    }

    @Test
    void keepsTheActivityInDraftWhenRedisAdmissionIsUnavailable() throws Exception {
        FlashSaleActivityView activity = draftActivity();

        mockMvc.perform(post("/api/v1/marketing/admin/flash-sales/{activityNo}/publish", activity.activityNo())
                        .with(jwt().jwt(token -> token.subject("1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLASH_SALE_ADMISSION_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_activity WHERE activity_no = ?",
                String.class,
                activity.activityNo())).isEqualTo("DRAFT");

        mockMvc.perform(get("/api/v1/marketing/status"))
                .andExpect(status().isOk());
    }

    @Test
    void neverFallsBackToLocalAdmissionWhenRedisIsUnavailable() throws Exception {
        FlashSaleActivityView activity = draftActivity();
        jdbcTemplate.update(
                "UPDATE flash_sale_activity SET status = 'ACTIVE' WHERE activity_no = ?",
                activity.activityNo());

        mockMvc.perform(post("/api/v1/marketing/flash-sales/{activityNo}/admissions", activity.activityNo())
                        .with(jwt().jwt(token -> token.subject("301"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", "flash-unavailable-301")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"501\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLASH_SALE_ADMISSION_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_admission "
                        + "WHERE activity_no = ? AND user_id = 301",
                String.class,
                activity.activityNo())).isEqualTo("ADMISSION_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_outbox_event",
                Integer.class)).isZero();
    }

    private FlashSaleActivityView draftActivity() {
        Instant now = Instant.now();
        return flashSaleService.create(new CreateFlashSaleCommand(
                "Unavailable admission",
                8003L,
                9003L,
                new BigDecimal("19.90"),
                10,
                now.minusSeconds(10),
                now.plusSeconds(600)));
    }
}
