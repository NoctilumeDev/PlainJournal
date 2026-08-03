package com.ecommerce.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class AddressFlowIntegrationTest {

    private static final String PASSWORD = "ReaderPass123";
    private static final String TRADE_INTERNAL_TOKEN =
            "test-trade-internal-token-with-at-least-32-characters";
    private static final String PAYMENT_INTERNAL_TOKEN =
            "test-payment-internal-token-with-at-least-32-characters";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    AddressFlowIntegrationTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM user_role");
        jdbcTemplate.update("DELETE FROM login_record");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    void managesDefaultAddressesAndProtectsTheInternalSnapshotEndpoint() throws Exception {
        UserSession owner = registerAndLogin("address-owner@example.com");
        UserSession other = registerAndLogin("address-other@example.com");

        JsonNode firstAddress = createAddress(owner.token(), "Old Street 1", false);
        JsonNode secondAddress = createAddress(owner.token(), "Lake Road 2", false);
        assertThat(firstAddress.at("/data/id").isTextual()).isTrue();
        assertThat(secondAddress.at("/data/id").isTextual()).isTrue();
        Long firstId = Long.parseLong(firstAddress.at("/data/id").asText());
        Long secondId = Long.parseLong(secondAddress.at("/data/id").asText());

        mockMvc.perform(get("/api/v1/identity/addresses")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].id").value(firstId.toString()))
                .andExpect(jsonPath("$.data[0].defaultAddress").value(true));

        mockMvc.perform(post("/api/v1/identity/addresses/{addressId}/default", secondId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultAddress").value(true));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_address WHERE user_id = ? AND is_default = TRUE",
                Integer.class, owner.id())).isEqualTo(1);

        String internalPath = "/api/v1/identity/internal/users/" + owner.id() + "/addresses/" + secondId;
        mockMvc.perform(get(internalPath)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(internalPath)
                        .header("X-Internal-Service", "payment-service")
                        .header("X-Internal-Token", TRADE_INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(internalPath)
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token", PAYMENT_INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(internalPath)
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token", TRADE_INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detailAddress").value("Lake Road 2"))
                .andExpect(jsonPath("$.data.provinceCode").value("330000"))
                .andExpect(jsonPath("$.data.cityCode").value("330100"))
                .andExpect(jsonPath("$.data.districtCode").value("330106"));

        mockMvc.perform(get("/api/v1/identity/internal/users/{userId}/addresses/{addressId}",
                        other.id(), secondId)
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token", TRADE_INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/identity/addresses/{addressId}", secondId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default FROM user_address WHERE id = ?", Boolean.class, firstId)).isTrue();
    }

    @Test
    void rejectsInvalidAddressInputAndCrossUserMutation() throws Exception {
        UserSession owner = registerAndLogin("address-owner@example.com");
        UserSession other = registerAndLogin("address-other@example.com");
        Long addressId = Long.parseLong(
                createAddress(owner.token(), "Old Street 1", false).at("/data/id").asText());

        Map<String, Object> invalid = address("Old Street 1", false);
        invalid.put("phone", "javascript:alert(1)");
        mockMvc.perform(post("/api/v1/identity/addresses")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/v1/identity/addresses/{addressId}", addressId)
                        .header("Authorization", bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(address("Changed", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    }

    private JsonNode createAddress(String token, String detailAddress, boolean setDefault) throws Exception {
        String response = mockMvc.perform(post("/api/v1/identity/addresses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(address(detailAddress, setDefault))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Map<String, Object> address(String detailAddress, boolean setDefault) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipientName", "Test Customer");
        address.put("phone", "+86 13800000000");
        address.put("province", "Zhejiang");
        address.put("provinceCode", "330000");
        address.put("city", "Hangzhou");
        address.put("cityCode", "330100");
        address.put("district", "Xihu");
        address.put("districtCode", "330106");
        address.put("detailAddress", detailAddress);
        address.put("postalCode", "310000");
        address.put("setDefault", setDefault);
        return address;
    }

    private UserSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/identity/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", PASSWORD,
                                "displayName", "Address Reader"))))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE email = ?", Long.class, email);
        return new UserSession(userId, objectMapper.readTree(response).at("/data/accessToken").asText());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record UserSession(Long id, String token) {
    }
}
