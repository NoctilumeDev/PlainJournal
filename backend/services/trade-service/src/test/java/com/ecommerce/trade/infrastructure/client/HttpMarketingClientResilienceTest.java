package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.MarketingPort.DeliveryRegion;
import com.ecommerce.trade.application.port.MarketingPort.PricingCommand;
import com.ecommerce.trade.application.port.MarketingPort.PricingLine;
import com.ecommerce.trade.application.port.MarketingPort.PricingLock;
import com.ecommerce.trade.application.port.MarketingPort.PricingRejectedException;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.ecommerce.trade.infrastructure.config.MarketingPricingLockResilienceProperties;
import com.ecommerce.trade.infrastructure.resilience.MarketingPricingLockFailure;
import com.ecommerce.trade.infrastructure.resilience.TradeMarketingPricingLockResilience;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMarketingClientResilienceTest {

    private static final String ORDER_NO = "ORDER-MARKETING-RESILIENCE-001";
    private static final String INTERNAL_TOKEN = "test-internal-service-token-with-at-least-32-characters";

    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<ExchangeResponder> responder = new AtomicReference<>();
    private ExecutorService serverExecutor;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            try {
                responder.get().respond(exchange);
            } catch (Exception ignored) {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void retriesACommittedCommandWhoseResponseWasLostAndReturnsTheSameLock() {
        responder.set(exchange -> {
            if (requestCount.get() == 1) {
                exchange.close();
            } else {
                respond(exchange, 200, successBody());
            }
        });
        Fixture fixture = fixture(Duration.ofMillis(200), 2, 2, 4, 2);

        PricingLock lock = fixture.client().lockPricing(command());

        assertThat(lock.lockNo()).isEqualTo("MKT-RESILIENCE-001");
        assertThat(lock.orderNo()).isEqualTo(ORDER_NO);
        assertThat(requestCount).hasValue(2);
        assertThat(fixture.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void doesNotRetryOrCountARecognizedBusinessRejection() {
        responder.set(exchange -> respond(exchange, 409,
                "{\"code\":\"BENEFIT_NOT_ELIGIBLE\",\"message\":\"rejected\"," +
                        "\"data\":null,\"timestamp\":\"2026-07-18T00:00:00Z\"}"));
        Fixture fixture = fixture(Duration.ofMillis(200), 2, 2, 4, 2);

        assertThatThrownBy(() -> fixture.client().lockPricing(command()))
                .isInstanceOf(PricingRejectedException.class);

        assertThat(requestCount).hasValue(1);
        assertThat(fixture.circuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void boundsSlowCommandsWithPerAttemptTimeoutAndFiniteRetry() {
        responder.set(exchange -> {
            Thread.sleep(300);
            respond(exchange, 200, successBody());
        });
        Fixture fixture = fixture(Duration.ofMillis(80), 2, 2, 4, 2);
        long startedAt = System.nanoTime();

        assertRemoteUnavailable(fixture);

        assertThat(requestCount).hasValue(2);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void recordsButDoesNotRetryAnInvalidSuccessfulResponse() {
        responder.set(exchange -> respond(exchange, 200, "{\"code\":\"OK\",\"data\":"));
        Fixture fixture = fixture(Duration.ofMillis(200), 2, 2, 4, 2);

        assertRemoteUnavailable(fixture);

        assertThat(requestCount).hasValue(1);
        assertThat(fixture.circuitBreaker().getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(fixture.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void rejectsASuccessfulPricingLockForADifferentOrder() {
        responder.set(exchange -> respond(exchange, 200, successBody("ORDER-OTHER")));
        Fixture fixture = fixture(Duration.ofMillis(200), 2, 2, 4, 2);

        assertRemoteUnavailable(fixture);

        assertThat(requestCount).hasValue(1);
        assertThat(fixture.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void opensTheCircuitAfterLogicalFailuresAndRecoversThroughHalfOpen() {
        responder.set(exchange -> respond(exchange, 503,
                "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"," +
                        "\"data\":null,\"timestamp\":\"2026-07-18T00:00:00Z\"}"));
        Fixture fixture = fixture(Duration.ofMillis(200), 1, 2, 2, 2);

        assertRemoteUnavailable(fixture);
        assertRemoteUnavailable(fixture);
        assertThat(fixture.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> fixture.client().lockPricing(command()))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.getCause())
                                .isInstanceOf(CallNotPermittedException.class));
        assertThat(requestCount).hasValue(2);
        assertThat(fixture.meters().find("ecommerce.http.client.resilience.rejections")
                .tag("guard", "circuit").counter().count()).isEqualTo(1);

        fixture.circuitBreaker().transitionToHalfOpenState();
        responder.set(exchange -> respond(exchange, 200, successBody()));
        assertThat(fixture.client().lockPricing(command()).lockNo()).isEqualTo("MKT-RESILIENCE-001");
        assertThat(fixture.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void rejectsExcessConcurrencyWithoutStartingAnotherRemoteCommand() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        responder.set(exchange -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            respond(exchange, 200, successBody());
        });
        Fixture fixture = fixture(Duration.ofSeconds(3), 1, 1, 4, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<PricingLock> first = caller.submit(() -> fixture.client().lockPricing(command()));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> fixture.client().lockPricing(command()))
                    .isInstanceOfSatisfying(TradeException.class,
                            exception -> assertThat(exception.getCause())
                                    .isInstanceOf(BulkheadFullException.class));
            assertThat(requestCount).hasValue(1);
            assertThat(fixture.meters().find("ecommerce.http.client.resilience.rejections")
                    .tag("guard", "bulkhead").counter().count()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).lockNo()).isEqualTo("MKT-RESILIENCE-001");
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void finiteBulkheadWaitAbsorbsABriefPricingLockBurst() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        responder.set(exchange -> {
            entered.countDown();
            Thread.sleep(75);
            respond(exchange, 200, successBody());
        });
        Fixture fixture = fixture(
                Duration.ofSeconds(1), 1, 1, Duration.ofMillis(250), 4, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<PricingLock> first = caller.submit(() -> fixture.client().lockPricing(command()));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.client().lockPricing(command()).lockNo())
                    .isEqualTo("MKT-RESILIENCE-001");
            assertThat(first.get(1, TimeUnit.SECONDS).lockNo())
                    .isEqualTo("MKT-RESILIENCE-001");
            assertThat(requestCount).hasValue(2);
            assertThat(fixture.meters().find("ecommerce.http.client.resilience.rejections")
                    .tag("guard", "bulkhead").counter().count()).isZero();
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void pricingRetryBackoffReleasesTheBulkheadPermit() throws Exception {
        MarketingPricingLockResilienceProperties properties =
                new MarketingPricingLockResilienceProperties(
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1),
                        2,
                        Duration.ofMillis(400),
                        1,
                        Duration.ofMillis(100),
                        10,
                        10,
                        50,
                        Duration.ofSeconds(1),
                        1);
        TradeMarketingPricingLockResilience resilience =
                new TradeMarketingPricingLockResilience(
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults(),
                        BulkheadRegistry.ofDefaults(),
                        new SimpleMeterRegistry(),
                        properties);
        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        AtomicInteger retriedCallAttempts = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> retried = caller.submit(() -> resilience.execute(() -> {
                if (retriedCallAttempts.incrementAndGet() == 1) {
                    firstAttemptEntered.countDown();
                    throw MarketingPricingLockFailure.transientFailure(
                            new IllegalStateException("retry after backoff"));
                }
                return "recovered";
            }));
            assertThat(firstAttemptEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(resilience.execute(() -> "independent")).isEqualTo("independent");
            assertThat(retried.get(2, TimeUnit.SECONDS)).isEqualTo("recovered");
            assertThat(retriedCallAttempts).hasValue(2);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void rejectsAStartupPolicyWhoseRetriesCanExceedTheDeclaredBudget() {
        MarketingPricingLockResilienceProperties properties =
                new MarketingPricingLockResilienceProperties(
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        2, Duration.ofMillis(100), 4, Duration.ZERO,
                        4, 2, 50, Duration.ofSeconds(1), 1);

        assertThatThrownBy(() -> new TradeMarketingPricingLockResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can exceed its total budget");
    }

    @Test
    void startupBudgetIncludesTheMaximumBulkheadWait() {
        MarketingPricingLockResilienceProperties properties =
                new MarketingPricingLockResilienceProperties(
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(700),
                        2,
                        Duration.ofMillis(50),
                        4,
                        Duration.ofMillis(100),
                        4,
                        2,
                        50,
                        Duration.ofSeconds(1),
                        1);

        assertThatThrownBy(() -> new TradeMarketingPricingLockResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can exceed its total budget");
    }

    private void assertRemoteUnavailable(Fixture fixture) {
        assertThatThrownBy(() -> fixture.client().lockPricing(command()))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
    }

    private Fixture fixture(
            Duration readTimeout,
            int maxAttempts,
            int maxConcurrentCalls,
            int slidingWindowSize,
            int minimumNumberOfCalls) {
        return fixture(
                readTimeout,
                maxAttempts,
                maxConcurrentCalls,
                Duration.ZERO,
                slidingWindowSize,
                minimumNumberOfCalls);
    }

    private Fixture fixture(
            Duration readTimeout,
            int maxAttempts,
            int maxConcurrentCalls,
            Duration bulkheadMaxWait,
            int slidingWindowSize,
            int minimumNumberOfCalls) {
        Duration connectTimeout = Duration.ofMillis(50);
        Duration retryWait = Duration.ofMillis(10);
        Duration totalBudget = connectTimeout.plus(readTimeout)
                .multipliedBy(maxAttempts)
                .plus(retryWait.multipliedBy(maxAttempts - 1L))
                .plus(bulkheadMaxWait)
                .plusMillis(100);
        MarketingPricingLockResilienceProperties properties =
                new MarketingPricingLockResilienceProperties(
                        connectTimeout, readTimeout, totalBudget, maxAttempts, retryWait,
                        maxConcurrentCalls, bulkheadMaxWait, slidingWindowSize,
                        minimumNumberOfCalls, 50, Duration.ofSeconds(1), 1);
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TradeMarketingPricingLockResilience resilience = new TradeMarketingPricingLockResilience(
                circuitBreakers,
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                meters,
                properties);

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .build();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HttpMarketingClient client = new HttpMarketingClient(
                restClient,
                new InternalClientProperties(INTERNAL_TOKEN, "trade-service"),
                objectMapper,
                resilience);
        return new Fixture(
                client,
                circuitBreakers.circuitBreaker(TradeMarketingPricingLockResilience.INSTANCE_NAME),
                meters);
    }

    private PricingCommand command() {
        return new PricingCommand(
                ORDER_NO,
                1001L,
                new BigDecimal("39.80"),
                new DeliveryRegion("330000", "330100", "330106"),
                List.of(new PricingLine(1, 2001L, new BigDecimal("39.80"))),
                List.of());
    }

    private String successBody() {
        return successBody(ORDER_NO);
    }

    private String successBody(String orderNo) {
        return "{\"code\":\"OK\",\"message\":\"success\",\"data\":{" +
                "\"lockNo\":\"MKT-RESILIENCE-001\",\"orderNo\":\"" + orderNo + "\"," +
                "\"userId\":1001,\"originalAmount\":39.80," +
                "\"couponDiscount\":0.00,\"redPacketDiscount\":0.00," +
                "\"subsidyDiscount\":0.00,\"discountAmount\":0.00," +
                "\"payableAmount\":39.80,\"status\":\"LOCKED\",\"appliedBenefits\":[]}," +
                "\"timestamp\":\"2026-07-18T00:00:00Z\"}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, content.length);
        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private record Fixture(
            HttpMarketingClient client,
            CircuitBreaker circuitBreaker,
            SimpleMeterRegistry meters) {
    }

    @FunctionalInterface
    private interface ExchangeResponder {
        void respond(HttpExchange exchange) throws Exception;
    }
}
