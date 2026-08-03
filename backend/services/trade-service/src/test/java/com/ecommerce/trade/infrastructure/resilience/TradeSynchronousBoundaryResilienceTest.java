package com.ecommerce.trade.infrastructure.resilience;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.config.SynchronousBoundaryResilienceProperties;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeSynchronousBoundaryResilienceTest {

    @Test
    void retriesQueriesButNeverRetriesInventoryCommands() {
        Fixture fixture = fixture(2, 4, 2, 4, 2);
        AtomicInteger queryCalls = new AtomicInteger();

        String value = fixture.resilience().execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                () -> {
                    if (queryCalls.incrementAndGet() == 1) {
                        throw RemoteDependencyFailure.transientFailure(
                                new IllegalStateException("temporary query failure"));
                    }
                    return "RESERVED";
                });

        assertThat(value).isEqualTo("RESERVED");
        assertThat(queryCalls).hasValue(2);

        AtomicInteger commandCalls = new AtomicInteger();
        assertThatThrownBy(() -> fixture.resilience().execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                () -> {
                    commandCalls.incrementAndGet();
                    throw RemoteDependencyFailure.transientFailure(
                            new IllegalStateException("unknown command result"));
                }))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
        assertThat(commandCalls).hasValue(1);
    }

    @Test
    void inventoryCommandCircuitDoesNotBlockAuthoritativeResultQueries() {
        Fixture fixture = fixture(1, 4, 2, 2, 2);
        AtomicInteger commandCalls = new AtomicInteger();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertRemoteUnavailable(() -> fixture.resilience().execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                    () -> {
                        commandCalls.incrementAndGet();
                        throw RemoteDependencyFailure.transientFailure(
                                new IllegalStateException("inventory command unavailable"));
                    }));
        }

        CircuitBreaker commandCircuit = fixture.circuitBreakers().circuitBreaker(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND.instanceName());
        assertThat(commandCircuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> fixture.resilience().execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                () -> "unexpected"))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.getCause())
                                .isInstanceOf(CallNotPermittedException.class));
        assertThat(commandCalls).hasValue(2);

        assertThat(fixture.resilience().execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                () -> "RELEASED")).isEqualTo("RELEASED");
        assertThat(fixture.circuitBreakers().circuitBreaker(
                        TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY.instanceName())
                .getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void rejectsExcessInventoryCommandsWithoutStartingRemoteIo() throws Exception {
        Fixture fixture = fixture(1, 4, 1, 4, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = caller.submit(() -> fixture.resilience().execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                    () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return "RESERVED";
                    }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> fixture.resilience().execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                    () -> {
                        calls.incrementAndGet();
                        return "unexpected";
                    }))
                    .isInstanceOfSatisfying(TradeException.class,
                            exception -> assertThat(exception.getCause())
                                    .isInstanceOf(BulkheadFullException.class));
            assertThat(calls).hasValue(1);
            assertThat(fixture.meters().find("ecommerce.http.client.resilience.rejections")
                    .tag("dependency", "inventory-service")
                    .tag("operation", "reservation_command")
                    .tag("guard", "bulkhead")
                    .counter().count()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("RESERVED");
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void finiteBulkheadWaitAbsorbsABriefInventoryCommandBurst() throws Exception {
        Fixture fixture = fixture(
                1, 4, 1, Duration.ofMillis(250), 4, 2);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = caller.submit(() -> fixture.resilience().execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                    () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            Thread.sleep(75);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return "RESERVED";
                    }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.resilience().execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                    () -> {
                        calls.incrementAndGet();
                        return "RESERVED";
                    })).isEqualTo("RESERVED");
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("RESERVED");
            assertThat(calls).hasValue(2);
            assertThat(fixture.meters().find("ecommerce.http.client.resilience.rejections")
                    .tag("dependency", "inventory-service")
                    .tag("operation", "reservation_command")
                    .tag("guard", "bulkhead")
                    .counter().count()).isZero();
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void queryRetryBackoffReleasesTheBulkheadPermit() throws Exception {
        RemoteClientProperties clientProperties = clientProperties(
                Duration.ofMillis(10), Duration.ofMillis(10));
        SynchronousBoundaryResilienceProperties properties =
                new SynchronousBoundaryResilienceProperties(
                        Duration.ofSeconds(1),
                        2,
                        Duration.ofMillis(400),
                        1,
                        1,
                        Duration.ofMillis(100),
                        10,
                        10,
                        50,
                        Duration.ofSeconds(1),
                        1);
        TradeSynchronousBoundaryResilience resilience =
                new TradeSynchronousBoundaryResilience(
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults(),
                        BulkheadRegistry.ofDefaults(),
                        new SimpleMeterRegistry(),
                        clientProperties,
                        properties);
        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        AtomicInteger retriedCallAttempts = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> retried = caller.submit(() -> resilience.execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                    () -> {
                        if (retriedCallAttempts.incrementAndGet() == 1) {
                            firstAttemptEntered.countDown();
                            throw RemoteDependencyFailure.transientFailure(
                                    new IllegalStateException("retry after backoff"));
                        }
                        return "recovered";
                    }));
            assertThat(firstAttemptEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(resilience.execute(
                    TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                    () -> "independent")).isEqualTo("independent");
            assertThat(retried.get(2, TimeUnit.SECONDS)).isEqualTo("recovered");
            assertThat(retriedCallAttempts).hasValue(2);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void rejectsAQueryPolicyThatCanExceedItsDeclaredBudget() {
        RemoteClientProperties clientProperties = clientProperties(
                Duration.ofMillis(500), Duration.ofSeconds(1));
        SynchronousBoundaryResilienceProperties properties = properties(
                Duration.ofSeconds(2), 2, 4, 2, Duration.ZERO, 4, 2);

        assertThatThrownBy(() -> new TradeSynchronousBoundaryResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                clientProperties,
                properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can exceed its total budget");
    }

    @Test
    void startupBudgetIncludesTheMaximumBulkheadWait() {
        RemoteClientProperties clientProperties = clientProperties(
                Duration.ofMillis(100), Duration.ofMillis(200));
        SynchronousBoundaryResilienceProperties properties =
                new SynchronousBoundaryResilienceProperties(
                        Duration.ofMillis(700),
                        2,
                        Duration.ofMillis(50),
                        4,
                        2,
                        Duration.ofMillis(100),
                        4,
                        2,
                        50,
                        Duration.ofSeconds(1),
                        1);

        assertThatThrownBy(() -> new TradeSynchronousBoundaryResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                clientProperties,
                properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can exceed its total budget");
    }

    private Fixture fixture(
            int queryAttempts,
            int queryConcurrency,
            int commandConcurrency,
            int slidingWindowSize,
            int minimumCalls) {
        return fixture(
                queryAttempts,
                queryConcurrency,
                commandConcurrency,
                Duration.ZERO,
                slidingWindowSize,
                minimumCalls);
    }

    private Fixture fixture(
            int queryAttempts,
            int queryConcurrency,
            int commandConcurrency,
            Duration bulkheadMaxWait,
            int slidingWindowSize,
            int minimumCalls) {
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RemoteClientProperties clientProperties = clientProperties(
                Duration.ofMillis(50), Duration.ofMillis(100));
        SynchronousBoundaryResilienceProperties properties = properties(
                Duration.ofSeconds(1),
                queryAttempts,
                queryConcurrency,
                commandConcurrency,
                bulkheadMaxWait,
                slidingWindowSize,
                minimumCalls);
        return new Fixture(
                new TradeSynchronousBoundaryResilience(
                        circuitBreakers,
                        RetryRegistry.ofDefaults(),
                        BulkheadRegistry.ofDefaults(),
                        meters,
                        clientProperties,
                        properties),
                circuitBreakers,
                meters);
    }

    private RemoteClientProperties clientProperties(Duration connectTimeout, Duration readTimeout) {
        return new RemoteClientProperties(
                connectTimeout,
                readTimeout,
                "http://catalog-service",
                "http://identity-service",
                "http://inventory-service",
                "http://marketing-service");
    }

    private SynchronousBoundaryResilienceProperties properties(
            Duration totalBudget,
            int queryAttempts,
            int queryConcurrency,
            int commandConcurrency,
            Duration bulkheadMaxWait,
            int slidingWindowSize,
            int minimumCalls) {
        return new SynchronousBoundaryResilienceProperties(
                totalBudget,
                queryAttempts,
                Duration.ofMillis(10),
                queryConcurrency,
                commandConcurrency,
                bulkheadMaxWait,
                slidingWindowSize,
                minimumCalls,
                50,
                Duration.ofSeconds(1),
                1);
    }

    private void assertRemoteUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
    }

    private record Fixture(
            TradeSynchronousBoundaryResilience resilience,
            CircuitBreakerRegistry circuitBreakers,
            SimpleMeterRegistry meters) {
    }
}
