package com.ecommerce.catalog;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.ReviewModels.CreateReviewCommand;
import com.ecommerce.catalog.application.model.ReviewModels.OrderCompletedEvent;
import com.ecommerce.catalog.application.model.ReviewModels.OrderLineSnapshot;
import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportReceipt;
import com.ecommerce.catalog.application.service.ProductReviewService;
import com.ecommerce.catalog.application.port.ObjectStorage;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class ProductReviewFlowIntegrationTest {

    private static final long PRODUCT_ID = 9_100_001L;
    private static final long SKU_ID = 9_200_001L;
    private static final long CUSTOMER_ID = 10_001L;
    private static final long OTHER_CUSTOMER_ID = 10_002L;
    private static final long THIRD_CUSTOMER_ID = 10_003L;
    private static final long ADMIN_ID = 20_001L;
    private static final String CONSUMER_GROUP = "catalog-review-test";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final ProductReviewService service;
    private final ProductReviewRepository repository;

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    ProductReviewFlowIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            ProductReviewService service,
            ProductReviewRepository repository) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.service = service;
        this.repository = repository;
    }

    @Test
    void databaseClockReturnsAnInstantThroughJdbcDriverConversion() {
        assertThat(repository.currentTime()).isNotNull();
    }

    @BeforeEach
    void createProductFacts() {
        jdbc.update("""
                INSERT INTO catalog_category
                    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
                VALUES (?, NULL, 'Review category', 'review-category', 'ACTIVE', 1, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, PRODUCT_ID + 10);
        jdbc.update("""
                INSERT INTO catalog_brand
                    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
                VALUES (?, 'Review brand', 'review-brand', NULL, 'ACTIVE', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, PRODUCT_ID + 20);
        jdbc.update("""
                INSERT INTO product_spu
                    (id, category_id, brand_id, title, subtitle, description, status,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, 'Review product', NULL, NULL, 'ACTIVE', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, PRODUCT_ID, PRODUCT_ID + 10, PRODUCT_ID + 20);
        jdbc.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, name, spec_json, sale_price, market_price,
                     status, version, created_at, updated_at)
                VALUES (?, ?, 'REVIEW-SKU', 'Review SKU', '{}', 19.90, 29.90,
                        'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, SKU_ID, PRODUCT_ID);
    }

    @AfterEach
    void cleanReviewFacts() {
        jdbc.update("DELETE FROM review_moderation_audit");
        jdbc.update("DELETE FROM review_report");
        jdbc.update("DELETE FROM review_like");
        jdbc.update("DELETE FROM review_reply");
        jdbc.update("DELETE FROM product_review");
        jdbc.update("DELETE FROM product_review_summary");
        jdbc.update("DELETE FROM review_eligibility");
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM consumer_failure");
        jdbc.update("DELETE FROM product_media");
        jdbc.update("DELETE FROM product_sku");
        jdbc.update("DELETE FROM product_spu");
        jdbc.update("DELETE FROM catalog_brand");
        jdbc.update("DELETE FROM catalog_category");
    }

    @Test
    void reviewCreationUsesReadCommittedForMySqlIdempotencyReplay() throws Exception {
        Transactional transaction = ProductReviewService.class
                .getMethod("createReview", CreateReviewCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void duplicateOrderCompletedCreatesOneEligibilityAndEnforcesOwnership() throws Exception {
        OrderCompletedEvent event = event(CUSTOMER_ID, "ORDER-REVIEW-001", 1);
        assertThat(service.acceptOrderCompleted(event, CONSUMER_GROUP)).isTrue();
        assertThat(service.acceptOrderCompleted(event, CONSUMER_GROUP)).isFalse();
        OrderCompletedEvent conflict = new OrderCompletedEvent(
                event.eventId(),
                "ORDER-REVIEW-CONFLICT",
                event.userId(),
                event.completedAt(),
                event.items());
        assertThatThrownBy(() -> service.acceptOrderCompleted(conflict, CONSUMER_GROUP))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT));

        assertThat(count("consumed_event")).isEqualTo(1);
        assertThat(count("review_eligibility")).isEqualTo(1);
        long eligibilityId = jdbc.queryForObject(
                "SELECT id FROM review_eligibility",
                Long.class);

        mockMvc.perform(get("/api/v1/catalog/review-eligibilities")
                        .with(customerJwt(CUSTOMER_ID))
                        .param("orderNo", event.orderNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data[0].productId").value(Long.toString(PRODUCT_ID)));
        mockMvc.perform(get("/api/v1/catalog/review-eligibilities")
                        .with(customerJwt(OTHER_CUSTOMER_ID))
                        .param("orderNo", event.orderNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/v1/catalog/reviews")
                        .with(customerJwt(OTHER_CUSTOMER_ID))
                        .header("Idempotency-Key", "review-cross-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eligibilityId", eligibilityId,
                                "rating", 5,
                                "content", "Cannot use another customer's eligibility.",
                                "anonymous", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void concurrentReviewRetriesConvergeAndIdempotencyConflictsAreRejected() throws Exception {
        service.acceptOrderCompleted(
                event(CUSTOMER_ID, "ORDER-REVIEW-CONCURRENT", 1),
                CONSUMER_GROUP);
        long eligibilityId = eligibilityId("ORDER-REVIEW-CONCURRENT");
        CreateReviewCommand command = new CreateReviewCommand(
                CUSTOMER_ID,
                eligibilityId,
                5,
                "The product matched the immutable order snapshot.",
                false,
                "review-concurrent-001");

        List<ProductReviewView> results = runConcurrently(
                16,
                () -> service.createReview(command));
        assertThat(results)
                .extracting(ProductReviewView::id)
                .containsOnly(results.get(0).id());
        assertThat(count("product_review")).isEqualTo(1);
        assertThat(count("product_review_summary")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT review_count FROM product_review_summary WHERE product_id = ?",
                Long.class,
                PRODUCT_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT rating_sum FROM product_review_summary WHERE product_id = ?",
                Long.class,
                PRODUCT_ID)).isEqualTo(5);

        assertThat(service.createReview(command).id()).isEqualTo(results.get(0).id());
        assertThatThrownBy(() -> service.createReview(new CreateReviewCommand(
                CUSTOMER_ID,
                eligibilityId,
                4,
                command.content(),
                false,
                command.idempotencyKey())))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> service.createReview(new CreateReviewCommand(
                CUSTOMER_ID,
                eligibilityId,
                5,
                command.content(),
                false,
                "review-another-key")))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.REVIEW_ALREADY_SUBMITTED));

        mockMvc.perform(get("/api/v1/catalog/products/{id}/review-summary", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.averageRating").value(5.0))
                .andExpect(jsonPath("$.data.rating5Count").value(1));
    }

    @Test
    void likesRepliesReportsAndModerationPreservePublishedSummary() throws Exception {
        long firstReviewId = createReview(
                CUSTOMER_ID,
                "ORDER-REVIEW-FIRST",
                5,
                "Accurate description and careful packaging.");
        long secondReviewId = createReview(
                OTHER_CUSTOMER_ID,
                "ORDER-REVIEW-SECOND",
                1,
                "The experience did not meet expectations.");

        List<ProductReviewView> likes = runConcurrently(
                8,
                () -> service.like(THIRD_CUSTOMER_ID, firstReviewId));
        assertThat(likes.get(likes.size() - 1).likeCount()).isEqualTo(1);
        assertThat(count("review_like")).isEqualTo(1);
        assertThat(service.unlike(THIRD_CUSTOMER_ID, firstReviewId).likeCount()).isZero();
        assertThat(service.unlike(THIRD_CUSTOMER_ID, firstReviewId).likeCount()).isZero();

        assertThatThrownBy(() -> service.report(
                CUSTOMER_ID,
                firstReviewId,
                "SPAM",
                "Self report must not be accepted."))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.REVIEW_ACTION_NOT_ALLOWED));

        ProductReviewView replied = service.reply(
                ADMIN_ID,
                firstReviewId,
                "reply-command-001",
                "Thank you. The packaging record was checked.");
        assertThat(replied.reply()).isNotNull();
        assertThat(service.reply(
                ADMIN_ID,
                firstReviewId,
                "reply-command-001",
                "Thank you. The packaging record was checked.").reply().id())
                .isEqualTo(replied.reply().id());
        assertThatThrownBy(() -> service.reply(
                ADMIN_ID,
                firstReviewId,
                "reply-command-001",
                "A different reply cannot reuse the command."))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> service.reply(
                ADMIN_ID + 1,
                firstReviewId,
                "reply-command-001",
                "Thank you. The packaging record was checked."))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT));

        ReviewReportReceipt report = service.report(
                OTHER_CUSTOMER_ID,
                firstReviewId,
                "FALSE_INFORMATION",
                "The report is reviewed by an authorized operator.");
        assertThat(service.report(
                OTHER_CUSTOMER_ID,
                firstReviewId,
                "FALSE_INFORMATION",
                "The report is reviewed by an authorized operator.").id())
                .isEqualTo(report.id());

        mockMvc.perform(get("/api/v1/catalog/admin/reviews/reports")
                        .with(adminJwt())
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewId")
                        .value(Long.toString(firstReviewId)));

        String moderationRequest = json(Map.of(
                "commandId", "moderate-command-001",
                "resolution", "UPHELD",
                "reason", "Verified against the immutable order and catalog facts."));
        mockMvc.perform(post(
                                "/api/v1/catalog/admin/reviews/reports/{id}/resolve",
                                report.id())
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderationRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatusBefore").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.reviewStatusAfter").value("HIDDEN"));
        mockMvc.perform(post(
                                "/api/v1/catalog/admin/reviews/reports/{id}/resolve",
                                report.id())
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moderationRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value("moderate-command-001"));
        assertThatThrownBy(() -> service.resolveReport(
                ADMIN_ID + 1,
                report.id(),
                "moderate-command-001",
                "UPHELD",
                "Verified against the immutable order and catalog facts."))
                .isInstanceOf(CatalogException.class)
                .satisfies(exception -> assertThat(((CatalogException) exception).error())
                        .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT));

        mockMvc.perform(get("/api/v1/catalog/products/{id}/reviews", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id")
                        .value(Long.toString(secondReviewId)));
        mockMvc.perform(get("/api/v1/catalog/products/{id}/review-summary", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.averageRating").value(1.0))
                .andExpect(jsonPath("$.data.rating1Count").value(1))
                .andExpect(jsonPath("$.data.rating5Count").value(0));

        mockMvc.perform(post("/api/v1/catalog/reviews/{id}/likes", firstReviewId)
                        .with(customerJwt(THIRD_CUSTOMER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_PUBLISHED"));
        mockMvc.perform(post("/api/v1/catalog/reviews/{id}/reports", firstReviewId)
                        .with(customerJwt(THIRD_CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "reasonCode", "OTHER",
                                "detail", "Hidden reviews cannot accept new reports."))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_PUBLISHED"));
        mockMvc.perform(delete("/api/v1/catalog/reviews/{id}/likes", firstReviewId)
                        .with(customerJwt(THIRD_CUSTOMER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_PUBLISHED"));
    }

    private long createReview(
            long userId,
            String orderNo,
            int rating,
            String content) {
        service.acceptOrderCompleted(event(userId, orderNo, 1), CONSUMER_GROUP);
        return service.createReview(new CreateReviewCommand(
                userId,
                eligibilityId(orderNo),
                rating,
                content,
                false,
                "review-" + orderNo)).id();
    }

    private OrderCompletedEvent event(long userId, String orderNo, int lineNo) {
        return new OrderCompletedEvent(
                UUID.randomUUID().toString(),
                orderNo,
                userId,
                Instant.parse("2026-07-24T12:00:00Z"),
                List.of(new OrderLineSnapshot(
                        lineNo,
                        PRODUCT_ID,
                        SKU_ID,
                        "Review product",
                        "REVIEW-SKU",
                        "Review SKU",
                        "{}",
                        null,
                        1)));
    }

    private long eligibilityId(String orderNo) {
        return jdbc.queryForObject(
                "SELECT id FROM review_eligibility WHERE order_no = ?",
                Long.class,
                orderNo);
    }

    private RequestPostProcessor customerJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId))
                        .claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(token -> token.subject(Long.toString(ADMIN_ID))
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, participants)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Concurrent review test start timed out");
                        }
                        return action.call();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(
                            "Concurrent review action failed",
                            exception);
                }
            }).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
