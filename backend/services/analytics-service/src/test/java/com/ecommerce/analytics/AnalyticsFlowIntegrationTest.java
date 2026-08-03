package com.ecommerce.analytics;

import com.ecommerce.analytics.application.exception.AnalyticsError;
import com.ecommerce.analytics.application.exception.AnalyticsException;
import com.ecommerce.analytics.application.model.AnalyticsModels.DomainEvent;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductLine;
import com.ecommerce.analytics.application.service.AnalyticsApplicationService;
import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class AnalyticsFlowIntegrationTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 24);
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-24T02:00:00Z");
    private static final long ADMIN_ID = 9001L;

    private final AnalyticsApplicationService service;
    private final JdbcTemplate jdbc;
    private final MockMvc mockMvc;
    private final AnalyticsRepository repository;

    @Autowired
    AnalyticsFlowIntegrationTest(
            AnalyticsApplicationService service,
            JdbcTemplate jdbc,
            MockMvc mockMvc,
            AnalyticsRepository repository) {
        this.service = service;
        this.jdbc = jdbc;
        this.mockMvc = mockMvc;
        this.repository = repository;
    }

    @Test
    void databaseClockReturnsAnInstantThroughJdbcDriverConversion() {
        assertThat(repository.currentTime()).isNotNull();
    }

    @AfterEach
    void cleanData() {
        jdbc.update("DELETE FROM analytics_rebuild_audit");
        jdbc.update("DELETE FROM analytics_product_summary");
        jdbc.update("DELETE FROM analytics_daily_summary");
        jdbc.update("DELETE FROM analytics_source_product_line");
        jdbc.update("DELETE FROM analytics_source_event");
        jdbc.update("DELETE FROM consumer_failure");
    }

    @Test
    void projectsSixFactsWithoutDoubleCountingAndExposesAnAuthorizedDashboard() throws Exception {
        DomainEvent created = event(
                "OrderCreated",
                "trade-service",
                "TradeOrder",
                "ORD-001",
                0,
                1001,
                "ORD-001",
                "70.00",
                List.of());
        assertThat(service.acceptDomainEvent(created, "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(created, "analytics-test")).isFalse();
        assertThat(service.acceptDomainEvent(event(
                "OrderCreated",
                "trade-service",
                "TradeOrder",
                "ORD-002",
                0,
                1002,
                "ORD-002",
                "30.00",
                List.of()), "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(event(
                "OrderClosed",
                "trade-service",
                "TradeOrder",
                "ORD-002",
                1,
                1002,
                "ORD-002",
                "30.00",
                List.of()), "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(event(
                "PaymentSucceeded",
                "payment-service",
                "PaymentOrder",
                "PAY-001",
                1,
                1001,
                "ORD-001",
                "70.00",
                List.of()), "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(event(
                "OrderCompleted",
                "trade-service",
                "TradeOrder",
                "ORD-001",
                4,
                1001,
                "ORD-001",
                "70.00",
                List.of(
                        line(1, 101, 1001, "棉麻收纳袋", "SKU-101", 2, "40.00"),
                        line(2, 102, 1002, "青瓷杯", "SKU-102", 1, "30.00"))),
                "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(event(
                "AfterSaleApplied",
                "trade-service",
                "AfterSaleOrder",
                "AS-001",
                0,
                1001,
                "ORD-001",
                "70.00",
                List.of()), "analytics-test")).isTrue();
        assertThat(service.acceptDomainEvent(event(
                "RefundSucceeded",
                "payment-service",
                "RefundOrder",
                "REF-001",
                1,
                1001,
                "ORD-001",
                "70.00",
                List.of()), "analytics-test")).isTrue();

        var dashboard = service.dashboard(BUSINESS_DATE, BUSINESS_DATE, 10);
        assertThat(dashboard.totals().createdOrderCount()).isEqualTo(2);
        assertThat(dashboard.totals().createdOrderAmount()).isEqualByComparingTo("100.00");
        assertThat(dashboard.totals().paymentCount()).isEqualTo(1);
        assertThat(dashboard.totals().paymentAmount()).isEqualByComparingTo("70.00");
        assertThat(dashboard.totals().completedOrderCount()).isEqualTo(1);
        assertThat(dashboard.totals().closedOrderCount()).isEqualTo(1);
        assertThat(dashboard.totals().afterSaleCount()).isEqualTo(1);
        assertThat(dashboard.totals().refundCount()).isEqualTo(1);
        assertThat(dashboard.totals().uniqueCustomers()).isEqualTo(2);
        assertThat(dashboard.topProducts()).hasSize(2);
        assertThat(dashboard.topProducts().get(0).productId()).isEqualTo(101);
        assertThat(dashboard.topProducts().get(0).netRevenue()).isEqualByComparingTo("40.00");
        assertThat(dashboard.freshness().sourceEventCount()).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_source_event",
                Long.class)).isEqualTo(7);

        mockMvc.perform(get("/api/v1/analytics/overview")
                        .param("from", BUSINESS_DATE.toString())
                        .param("to", BUSINESS_DATE.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analytics/overview")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE")))
                        .param("from", BUSINESS_DATE.toString())
                        .param("to", BUSINESS_DATE.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/analytics/overview")
                        .with(operatorJwt())
                        .param("from", BUSINESS_DATE.toString())
                        .param("to", BUSINESS_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.createdOrderCount").value(2))
                .andExpect(jsonPath("$.data.totals.refundAmount").value(70.0))
                .andExpect(jsonPath("$.data.topProducts[0].productId").value("101"))
                .andExpect(jsonPath("$.data.topProducts.length()").value(2));
    }

    @Test
    void legacyCompletedEventsRemainVisibleWithoutInventingProductRevenue() {
        DomainEvent legacy = event(
                "OrderCompleted",
                "trade-service",
                "TradeOrder",
                "ORD-LEGACY",
                3,
                1001,
                "ORD-LEGACY",
                "25.00",
                List.of(line(1, 201, 2001, "旧事件商品", "SKU-201", 1, null)));

        assertThat(service.acceptDomainEvent(legacy, "analytics-test")).isTrue();
        var product = service.dashboard(BUSINESS_DATE, BUSINESS_DATE, 10)
                .topProducts()
                .get(0);
        assertThat(product.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(product.completedOrderCount()).isEqualTo(1);
        assertThat(product.revenueCoveredOrderCount()).isZero();
    }

    @Test
    void concurrentDuplicateEventsConvergeAndConflictsAreRejected() throws Exception {
        DomainEvent event = event(
                "PaymentSucceeded",
                "payment-service",
                "PaymentOrder",
                "PAY-CONCURRENT",
                1,
                1001,
                "ORD-CONCURRENT",
                "88.00",
                List.of());
        List<Boolean> results = runConcurrently(
                8,
                () -> service.acceptDomainEvent(event, "analytics-concurrent"));
        assertThat(results).containsExactlyInAnyOrder(
                true, false, false, false, false, false, false, false);
        assertThat(jdbc.queryForObject(
                "SELECT payment_count FROM analytics_daily_summary",
                Long.class)).isEqualTo(1);

        DomainEvent conflict = new DomainEvent(
                event.eventId(),
                event.eventType(),
                event.producer(),
                event.aggregateType(),
                event.aggregateId(),
                event.aggregateVersion(),
                event.occurredAt(),
                event.userId(),
                event.orderNo(),
                new BigDecimal("99.00"),
                "different-fingerprint",
                List.of());
        assertThatThrownBy(() -> service.acceptDomainEvent(
                conflict,
                "analytics-concurrent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting analytics source event identity");
    }

    @Test
    void reconciliationDetectsInjectedDriftAndAuditedRebuildConvergesIdempotently() {
        service.acceptDomainEvent(event(
                "OrderCreated",
                "trade-service",
                "TradeOrder",
                "ORD-REBUILD",
                0,
                1001,
                "ORD-REBUILD",
                "50.00",
                List.of()), "analytics-test");
        service.acceptDomainEvent(event(
                "OrderCompleted",
                "trade-service",
                "TradeOrder",
                "ORD-REBUILD",
                4,
                1001,
                "ORD-REBUILD",
                "50.00",
                List.of(line(1, 301, 3001, "重建商品", "SKU-301", 2, "50.00"))),
                "analytics-test");

        jdbc.update("""
                UPDATE analytics_daily_summary
                SET created_order_count = 99
                WHERE business_date = ?
                """, BUSINESS_DATE);
        jdbc.update("""
                DELETE FROM analytics_product_summary
                WHERE business_date = ? AND product_id = 301
                """, BUSINESS_DATE);
        jdbc.update("""
                INSERT INTO analytics_product_summary
                    (business_date, product_id, product_title, completed_order_count,
                     units_sold, net_revenue, revenue_covered_order_count, updated_at)
                VALUES (?, 999, '孤儿行', 1, 1, 1.00, 1, CURRENT_TIMESTAMP)
                """, BUSINESS_DATE);

        var reconciliation = service.reconcile(BUSINESS_DATE, BUSINESS_DATE);
        assertThat(reconciliation.saturated()).isFalse();
        assertThat(reconciliation.issueCount()).isEqualTo(3);
        assertThat(reconciliation.issues())
                .extracting(issue -> issue.projection() + ':' + issue.issueType())
                .containsExactlyInAnyOrder(
                        "DAILY:STALE",
                        "PRODUCT:MISSING",
                        "PRODUCT:ORPHAN");

        var first = service.rebuild(
                ADMIN_ID,
                "analytics-rebuild-20260724",
                "修复注入的日汇总和商品汇总偏差",
                BUSINESS_DATE,
                BUSINESS_DATE);
        assertThat(first.beforeIssueCount()).isEqualTo(3);
        assertThat(first.afterIssueCount()).isZero();
        assertThat(service.reconcile(BUSINESS_DATE, BUSINESS_DATE).issueCount()).isZero();
        assertThat(service.dashboard(BUSINESS_DATE, BUSINESS_DATE, 10)
                .topProducts())
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.productId()).isEqualTo(301);
                    assertThat(product.netRevenue()).isEqualByComparingTo("50.00");
                });

        var repeated = service.rebuild(
                ADMIN_ID,
                "analytics-rebuild-20260724",
                "修复注入的日汇总和商品汇总偏差",
                BUSINESS_DATE,
                BUSINESS_DATE);
        assertThat(repeated).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_rebuild_audit",
                Long.class)).isEqualTo(1);

        assertThatThrownBy(() -> service.rebuild(
                ADMIN_ID,
                "analytics-rebuild-20260724",
                "另一条不同的重建请求说明",
                BUSINESS_DATE,
                BUSINESS_DATE))
                .isInstanceOf(AnalyticsException.class)
                .satisfies(exception -> assertThat(((AnalyticsException) exception).error())
                        .isEqualTo(AnalyticsError.IDEMPOTENCY_CONFLICT));
    }

    private DomainEvent event(
            String eventType,
            String producer,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            long userId,
            String orderNo,
            String amount,
            List<ProductLine> productLines) {
        String eventId = UUID.randomUUID().toString();
        return new DomainEvent(
                eventId,
                eventType,
                producer,
                aggregateType,
                aggregateId,
                aggregateVersion,
                OCCURRED_AT,
                userId,
                orderNo,
                new BigDecimal(amount),
                "fingerprint-" + eventId,
                productLines);
    }

    private ProductLine line(
            int lineNo,
            long productId,
            long skuId,
            String title,
            String skuCode,
            long quantity,
            String payableAmount) {
        return new ProductLine(
                lineNo,
                productId,
                skuId,
                title,
                skuCode,
                quantity,
                payableAmount == null ? null : new BigDecimal(payableAmount));
    }

    private RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(builder -> builder
                        .subject(Long.toString(ADMIN_ID))
                        .claim("roles", List.of("OPERATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    private <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, count)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent test did not start");
                        }
                        return task.call();
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
        }
    }
}
