package com.ecommerce.marketing;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.FlashSaleModels.CreateFlashSaleCommand;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleActivityView;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleAdmissionView;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStore;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStoreException;
import com.ecommerce.marketing.application.service.FlashSaleAdmissionRecoveryJob;
import com.ecommerce.marketing.application.service.FlashSaleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(FlashSaleFlowIntegrationTest.AdmissionTestConfiguration.class)
class FlashSaleFlowIntegrationTest {

    private final FlashSaleService flashSaleService;
    private final FlashSaleAdmissionRecoveryJob recoveryJob;
    private final InMemoryFlashSaleAdmissionStore admissionStore;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    FlashSaleFlowIntegrationTest(
            FlashSaleService flashSaleService,
            FlashSaleAdmissionRecoveryJob recoveryJob,
            InMemoryFlashSaleAdmissionStore admissionStore,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper) {
        this.flashSaleService = flashSaleService;
        this.recoveryJob = recoveryJob;
        this.admissionStore = admissionStore;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM flash_sale_outbox_event");
        jdbcTemplate.update("DELETE FROM flash_sale_admission");
        jdbcTemplate.update("DELETE FROM flash_sale_activity");
        admissionStore.clear();
    }

    @Test
    void createsPublishesAndProtectsFlashSaleRoutes() throws Exception {
        Instant now = Instant.now();
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "M6 admission baseline",
                "productId", "8001",
                "skuId", "9001",
                "salePrice", "39.90",
                "admissionLimit", 3,
                "startsAt", now.minusSeconds(10),
                "endsAt", now.plusSeconds(600)));

        mockMvc.perform(post("/api/v1/marketing/admin/flash-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/marketing/admin/flash-sales")
                        .with(jwt().jwt(token -> token.subject("101"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        String activityNo = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/marketing/admin/flash-sales")
                                .with(jwt().jwt(token -> token.subject("1"))
                                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.skuId").isString())
                .andReturn().getResponse().getContentAsString())
                .path("data").path("activityNo").asText();

        mockMvc.perform(get("/api/v1/marketing/flash-sales/{activityNo}", activityNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/marketing/admin/flash-sales/{activityNo}/publish", activityNo)
                        .with(jwt().jwt(token -> token.subject("2"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        String token = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/marketing/flash-sales/{activityNo}/admissions", activityNo)
                                .with(jwt().jwt(jwt -> jwt.subject("101"))
                                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                                .header("Idempotency-Key", "flash-request-101")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"addressId\":\"501\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.userId").isString())
                .andReturn().getResponse().getContentAsString())
                .path("data").path("requestToken").asText();

        mockMvc.perform(get("/api/v1/marketing/flash-sales/admissions/{token}", token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/marketing/flash-sales/admissions/{token}", token)
                        .with(jwt().jwt(jwt -> jwt.subject("102"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/marketing/flash-sales/admissions/{token}", token)
                        .with(jwt().jwt(jwt -> jwt.subject("101"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestToken").value(token));
    }

    @Test
    void protectsPrometheusWithTheDedicatedScrapeIdentity() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token", "wrong-metrics-token-with-at-least-32-characters"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Metrics-Token",
                                "test-only-metrics-scrape-token-with-at-least-32-characters"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("ecommerce_outbox_pending_events")));
    }

    @Test
    void replaysTheStableTokenForTheSameRequestAndTheSameUser() {
        FlashSaleActivityView activity = activeActivity(3);

        FlashSaleAdmissionView first = flashSaleService.admit(
                201L, activity.activityNo(), "flash-request-201-a", 501L);
        FlashSaleAdmissionView sameRequest = flashSaleService.admit(
                201L, activity.activityNo(), "flash-request-201-a", 501L);
        FlashSaleAdmissionView sameUserDifferentRequest = flashSaleService.admit(
                201L, activity.activityNo(), "flash-request-201-b", 501L);

        assertThat(sameRequest.requestToken()).isEqualTo(first.requestToken());
        assertThat(sameUserDifferentRequest.requestToken()).isEqualTo(first.requestToken());
        assertThat(sameRequest.acceptedAt()).isEqualTo(first.acceptedAt());
        assertThat(sameUserDifferentRequest.acceptedAt()).isEqualTo(first.acceptedAt());
        assertThat(admissionStore.acceptedCount(activity.activityNo())).isOne();
    }

    @Test
    void persistsThePendingFactBeforeRedisAndKeepsExternalCallsOutsideTransactions() {
        FlashSaleActivityView activity = activeActivity(3);

        FlashSaleAdmissionView admission = flashSaleService.admit(
                250L, activity.activityNo(), "flash-request-250", 550L);

        assertThat(admission.status()).isEqualTo("QUEUED");
        assertThat(admissionStore.pendingFactObserved()).isTrue();
        assertThat(admissionStore.preheatTransactionObserved()).isFalse();
        assertThat(admissionStore.admitTransactionObserved()).isFalse();
    }

    @Test
    void recoversAResultUnknownRedisAcceptanceWithoutAClientRetry() {
        FlashSaleActivityView activity = activeActivity(3);
        admissionStore.failAfterAcceptanceOnce();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> flashSaleService.admit(
                        260L, activity.activityNo(), "flash-request-260", 560L))
                .isInstanceOf(MarketingException.class)
                .extracting(exception -> ((MarketingException) exception).error())
                .isEqualTo(MarketingError.FLASH_SALE_ADMISSION_UNAVAILABLE);

        String requestToken = jdbcTemplate.queryForObject(
                "SELECT request_token FROM flash_sale_admission "
                        + "WHERE activity_no = ? AND user_id = 260",
                String.class,
                activity.activityNo());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_admission WHERE request_token = ?",
                String.class,
                requestToken)).isEqualTo("ADMISSION_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_outbox_event WHERE aggregate_id = ?",
                Integer.class,
                requestToken)).isZero();

        recoveryJob.recoverPendingAdmissions();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM flash_sale_admission WHERE request_token = ?",
                String.class,
                requestToken)).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_outbox_event WHERE aggregate_id = ?",
                Integer.class,
                requestToken)).isOne();
        assertThat(admissionStore.acceptedCount(activity.activityNo())).isOne();
    }

    @Test
    void neverAdmitsMoreThanTheFixedQuotaUnderConcurrency() throws Exception {
        int quota = 20;
        int requests = 60;
        FlashSaleActivityView activity = activeActivity(quota);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                long userId = 1000L + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return flashSaleService.admit(
                                userId,
                                activity.activityNo(),
                                "flash-concurrent-" + userId,
                                5000L + userId).requestToken();
                    } catch (MarketingException exception) {
                        assertThat(exception.error()).isEqualTo(MarketingError.FLASH_SALE_SOLD_OUT);
                        return null;
                    }
                }));
            }
            ready.await();
            start.countDown();

            List<String> accepted = new ArrayList<>();
            for (Future<String> future : futures) {
                String token = future.get();
                if (token != null) {
                    accepted.add(token);
                }
            }
            assertThat(accepted).hasSize(quota).doesNotHaveDuplicates();
            assertThat(admissionStore.acceptedCount(activity.activityNo())).isEqualTo(quota);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flash_sale_admission WHERE status = 'QUEUED'",
                    Integer.class)).isEqualTo(quota);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flash_sale_admission "
                            + "WHERE status = 'ADMISSION_REJECTED' "
                            + "AND failure_code = 'FLASH_SALE_SOLD_OUT'",
                    Integer.class)).isEqualTo(requests - quota);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flash_sale_admission "
                            + "WHERE status = 'ADMISSION_PENDING'",
                    Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flash_sale_outbox_event "
                            + "WHERE event_type = 'FlashSaleAdmissionAccepted'",
                    Integer.class)).isEqualTo(quota);
        } finally {
            executor.shutdownNow();
        }
    }

    private FlashSaleActivityView activeActivity(int admissionLimit) {
        Instant now = Instant.now();
        FlashSaleActivityView draft = flashSaleService.create(new CreateFlashSaleCommand(
                "Concurrent admission",
                8002L,
                9002L,
                new BigDecimal("29.90"),
                admissionLimit,
                now.minusSeconds(10),
                now.plusSeconds(600)));
        return flashSaleService.publish(draft.activityNo());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdmissionTestConfiguration {

        @Bean
        @Primary
        InMemoryFlashSaleAdmissionStore inMemoryFlashSaleAdmissionStore(
                JdbcTemplate jdbcTemplate) {
            return new InMemoryFlashSaleAdmissionStore(jdbcTemplate);
        }
    }

    static final class InMemoryFlashSaleAdmissionStore implements FlashSaleAdmissionStore {

        private final JdbcTemplate jdbcTemplate;
        private final Map<String, ActivityState> activities = new ConcurrentHashMap<>();
        private final Map<String, String> userTokens = new ConcurrentHashMap<>();
        private final Map<String, String> requestTokens = new ConcurrentHashMap<>();
        private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
        private boolean preheatTransactionObserved;
        private boolean admitTransactionObserved;
        private boolean pendingFactObserved;
        private boolean failAfterAcceptance;

        private InMemoryFlashSaleAdmissionStore(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public synchronized void preheat(Activity activity, Instant now) {
            preheatTransactionObserved |=
                    TransactionSynchronizationManager.isActualTransactionActive();
            activities.putIfAbsent(activity.activityNo(), new ActivityState(activity));
        }

        @Override
        public synchronized Decision admit(
                String activityNo,
                Long userId,
                String requestKey,
                String candidateToken,
                Instant now) {
            admitTransactionObserved |=
                    TransactionSynchronizationManager.isActualTransactionActive();
            pendingFactObserved |= jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flash_sale_admission "
                            + "WHERE request_token = ? AND status = 'ADMISSION_PENDING'",
                    Integer.class,
                    candidateToken) == 1;
            ActivityState state = activities.get(activityNo);
            String requestIdentity = activityNo + ":" + userId + ":" + requestKey;
            String replay = requestTokens.get(requestIdentity);
            if (replay == null) {
                replay = userTokens.get(activityNo + ":" + userId);
                if (replay != null) {
                    requestTokens.put(requestIdentity, replay);
                }
            }
            if (replay != null) {
                Snapshot snapshot = snapshots.get(replay);
                return new Decision(
                        Outcome.REPLAYED,
                        replay,
                        snapshot.remainingAdmissions(),
                        snapshot.acceptedAt());
            }
            if (state == null) {
                return new Decision(Outcome.NOT_READY, null, -1, null);
            }
            if (now.isBefore(state.activity().startsAt())) {
                return new Decision(Outcome.NOT_STARTED, null, state.remaining().get(), null);
            }
            if (!now.isBefore(state.activity().endsAt())) {
                return new Decision(Outcome.ENDED, null, state.remaining().get(), null);
            }
            if (state.remaining().get() <= 0) {
                return new Decision(Outcome.SOLD_OUT, null, 0, null);
            }
            int remaining = state.remaining().decrementAndGet();
            userTokens.put(activityNo + ":" + userId, candidateToken);
            requestTokens.put(requestIdentity, candidateToken);
            snapshots.put(candidateToken, new Snapshot(
                    candidateToken,
                    activityNo,
                    userId,
                    Outcome.ACCEPTED.name(),
                    remaining,
                    now));
            if (failAfterAcceptance) {
                failAfterAcceptance = false;
                throw new FlashSaleAdmissionStoreException(
                        "Simulated lost Redis admission response");
            }
            return new Decision(Outcome.ACCEPTED, candidateToken, remaining, now);
        }

        @Override
        public Optional<Snapshot> find(String requestToken) {
            return Optional.ofNullable(snapshots.get(requestToken));
        }

        int acceptedCount(String activityNo) {
            ActivityState state = activities.get(activityNo);
            return state == null ? 0 : state.activity().admissionLimit() - state.remaining().get();
        }

        boolean preheatTransactionObserved() {
            return preheatTransactionObserved;
        }

        boolean admitTransactionObserved() {
            return admitTransactionObserved;
        }

        boolean pendingFactObserved() {
            return pendingFactObserved;
        }

        void failAfterAcceptanceOnce() {
            failAfterAcceptance = true;
        }

        void clear() {
            activities.clear();
            userTokens.clear();
            requestTokens.clear();
            snapshots.clear();
            preheatTransactionObserved = false;
            admitTransactionObserved = false;
            pendingFactObserved = false;
            failAfterAcceptance = false;
        }

        private record ActivityState(Activity activity, AtomicInteger remaining) {
            private ActivityState(Activity activity) {
                this(activity, new AtomicInteger(
                        activity.admissionLimit() - activity.admittedCount()));
            }
        }
    }
}
