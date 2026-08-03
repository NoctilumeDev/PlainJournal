package com.ecommerce.identity;

import com.ecommerce.identity.application.model.LoginContext;
import com.ecommerce.identity.application.port.LoginAttemptStore;
import com.ecommerce.identity.application.service.AuthenticationService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class AuthenticationFlowIntegrationTest {

    private static final String EMAIL = "reader@example.com";
    private static final String PASSWORD = "ReaderPass123";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final LoginAttemptStore loginAttemptStore;

    @Autowired
    AuthenticationFlowIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            LoginAttemptStore loginAttemptStore) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.loginAttemptStore = loginAttemptStore;
    }

    @AfterEach
    void cleanIdentityData() {
        loginAttemptStore.clear(EMAIL);
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM user_role");
        jdbcTemplate.update("DELETE FROM login_record");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    void locksEmailAfterFiveFailedLoginAttemptsAndAllowsExplicitReset() throws Exception {
        register(EMAIL);
        for (int attempt = 1; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/identity/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("email", EMAIL, "password", "WrongPass123"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", EMAIL, "password", "WrongPass123"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.code").value("LOGIN_TEMPORARILY_LOCKED"));

        mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_TEMPORARILY_LOCKED"));

        loginAttemptStore.clear(EMAIL);
        login(EMAIL);
    }

    @Test
    void protectsCurrentUserEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void loginDoesNotHoldADatabaseTransactionAcrossPasswordAndRedisWork() throws Exception {
        Transactional transaction = AuthenticationService.class
                .getMethod("login", String.class, String.class, LoginContext.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNull();
    }

    @Test
    void completesRegistrationLoginRefreshAndLogout() throws Exception {
        mockMvc.perform(post("/api/v1/identity/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "READER@example.com",
                                "password", PASSWORD,
                                "displayName", "First Reader"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));

        mockMvc.perform(post("/api/v1/identity/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", EMAIL,
                                "password", PASSWORD,
                                "displayName", "Duplicate Reader"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", EMAIL, "password", "WrongPass123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        JsonNode login = responseJson(mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "identity-integration-test")
                        .content(json(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString());

        String accessToken = login.at("/data/accessToken").asText();
        String firstRefreshToken = login.at("/data/refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(firstRefreshToken).isNotBlank();

        mockMvc.perform(get("/api/v1/identity/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));

        JsonNode refresh = responseJson(mockMvc.perform(post("/api/v1/identity/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", firstRefreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String secondRefreshToken = refresh.at("/data/refreshToken").asText();
        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);

        assertRefreshRejected(firstRefreshToken);

        mockMvc.perform(post("/api/v1/identity/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", secondRefreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        assertRefreshRejected(secondRefreshToken);

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_account WHERE email = ?",
                String.class,
                EMAIL
        );
        assertThat(passwordHash).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_record WHERE normalized_email = ?",
                Integer.class,
                EMAIL
        )).isEqualTo(2);
        jdbcTemplate.query("SELECT token_hash FROM refresh_token", resultSet -> {
            assertThat(resultSet.getString(1)).hasSize(64).isNotEqualTo(firstRefreshToken);
        });
    }

    @Test
    void allowsOnlyOneConcurrentRefreshForTheSameToken() throws Exception {
        register(EMAIL);
        String refreshToken = login(EMAIL).at("/data/refreshToken").asText();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> refreshResult(refreshToken, start));
            Future<String> second = executor.submit(() -> refreshResult(refreshToken, start));
            start.countDown();

            assertThat(first.get()).isIn("OK", "INVALID_REFRESH_TOKEN");
            assertThat(second.get()).isIn("OK", "INVALID_REFRESH_TOKEN");
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("OK", "INVALID_REFRESH_TOKEN");
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/identity/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/identity/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "password", PASSWORD,
                                "displayName", "Concurrent Reader"
                        ))))
                .andExpect(status().isCreated());
    }

    private JsonNode login(String email) throws Exception {
        return responseJson(mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String refreshResult(String refreshToken, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            String response = mockMvc.perform(post("/api/v1/identity/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("refreshToken", refreshToken))))
                    .andReturn().getResponse().getContentAsString();
            return responseJson(response).path("code").asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode responseJson(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
