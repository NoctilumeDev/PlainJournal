package com.ecommerce.trade.infrastructure.resilience;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.config.SynchronousBoundaryResilienceProperties;
import com.ecommerce.platform.common.transaction.SynchronousBoundaryGuard;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class TradeSynchronousBoundaryResilience {

    private final Map<Boundary, Guard> guards = new EnumMap<>(Boundary.class);

    public TradeSynchronousBoundaryResilience(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry,
            RemoteClientProperties clientProperties,
            SynchronousBoundaryResilienceProperties properties) {
        Objects.requireNonNull(circuitBreakerRegistry, "circuitBreakerRegistry");
        Objects.requireNonNull(retryRegistry, "retryRegistry");
        Objects.requireNonNull(bulkheadRegistry, "bulkheadRegistry");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        validateBudget(clientProperties, properties);
        for (Boundary boundary : Boundary.values()) {
            guards.put(boundary, guard(
                    boundary,
                    circuitBreakerRegistry,
                    retryRegistry,
                    bulkheadRegistry,
                    meterRegistry,
                    properties));
        }
    }

    public <T> T execute(Boundary boundary, Supplier<T> remoteCall) {
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(remoteCall, "remoteCall");
        SynchronousBoundaryGuard.requireOutsideTransaction(boundary.instanceName());
        Guard guard = guards.get(boundary);
        Supplier<T> guarded = Bulkhead.decorateSupplier(guard.bulkhead(), remoteCall);
        guarded = Retry.decorateSupplier(guard.retry(), guarded);
        guarded = CircuitBreaker.decorateSupplier(guard.circuitBreaker(), guarded);
        try {
            return guarded.get();
        } catch (TradeException exception) {
            throw exception;
        } catch (BulkheadFullException exception) {
            guard.bulkheadRejected().increment();
            throw unavailable(exception);
        } catch (CallNotPermittedException exception) {
            guard.circuitRejected().increment();
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private Guard guard(
            Boundary boundary,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry,
            SynchronousBoundaryResilienceProperties properties) {
        int maxAttempts = boundary.retryQueries() ? properties.queryMaxAttempts() : 1;
        int maxConcurrentCalls = boundary.retryQueries()
                ? properties.queryMaxConcurrentCalls()
                : properties.commandMaxConcurrentCalls();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(properties.retryWait())
                .retryOnException(TradeSynchronousBoundaryResilience::isRetryable)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .recordException(TradeSynchronousBoundaryResilience::isRecordable)
                .ignoreException(TradeSynchronousBoundaryResilience::isIgnored)
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(properties.bulkheadMaxWait())
                .build();
        return new Guard(
                circuitBreakerRegistry.circuitBreaker(boundary.instanceName(), circuitBreakerConfig),
                retryRegistry.retry(boundary.instanceName(), retryConfig),
                bulkheadRegistry.bulkhead(boundary.instanceName(), bulkheadConfig),
                rejectionCounter(meterRegistry, boundary, "bulkhead"),
                rejectionCounter(meterRegistry, boundary, "circuit"));
    }

    private static boolean isRetryable(Throwable throwable) {
        return throwable instanceof RemoteDependencyFailure failure && failure.retryable();
    }

    private static boolean isRecordable(Throwable throwable) {
        return throwable instanceof RemoteDependencyFailure failure && failure.recordable();
    }

    private static boolean isIgnored(Throwable throwable) {
        return throwable instanceof TradeException
                || throwable instanceof BulkheadFullException
                || throwable instanceof RemoteDependencyFailure failure && !failure.recordable();
    }

    private static void validateBudget(
            RemoteClientProperties clientProperties,
            SynchronousBoundaryResilienceProperties properties) {
        Duration maximumAttemptTime = clientProperties.connectTimeout().plus(clientProperties.readTimeout());
        Duration maximumRetryWait = properties.retryWait().multipliedBy(properties.queryMaxAttempts() - 1L);
        Duration configuredWorstCase = maximumAttemptTime
                .multipliedBy(properties.queryMaxAttempts())
                .plus(maximumRetryWait)
                .plus(properties.bulkheadMaxWait().multipliedBy(properties.queryMaxAttempts()));
        if (configuredWorstCase.compareTo(properties.totalBudget()) > 0) {
            throw new IllegalArgumentException(
                    "Trade synchronous query policy can exceed its total budget: worstCase="
                            + configuredWorstCase + ", totalBudget=" + properties.totalBudget());
        }
    }

    private static Counter rejectionCounter(
            MeterRegistry meterRegistry,
            Boundary boundary,
            String guard) {
        return Counter.builder("ecommerce.http.client.resilience.rejections")
                .description("Synchronous dependency calls rejected before starting remote I/O")
                .tag("service", "trade-service")
                .tag("dependency", boundary.dependency())
                .tag("operation", boundary.operation())
                .tag("guard", guard)
                .register(meterRegistry);
    }

    private static TradeException unavailable(RuntimeException exception) {
        return new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
    }

    public enum Boundary {
        CATALOG_QUERY("tradeCatalogQuery", "catalog-service", "product_query", true),
        IDENTITY_QUERY("tradeIdentityQuery", "identity-service", "address_query", true),
        INVENTORY_QUERY("tradeInventoryQuery", "inventory-service", "inventory_query", true),
        INVENTORY_COMMAND("tradeInventoryCommand", "inventory-service", "reservation_command", false);

        private final String instanceName;
        private final String dependency;
        private final String operation;
        private final boolean retryQueries;

        Boundary(
                String instanceName,
                String dependency,
                String operation,
                boolean retryQueries) {
            this.instanceName = instanceName;
            this.dependency = dependency;
            this.operation = operation;
            this.retryQueries = retryQueries;
        }

        public String instanceName() {
            return instanceName;
        }

        String dependency() {
            return dependency;
        }

        String operation() {
            return operation;
        }

        boolean retryQueries() {
            return retryQueries;
        }
    }

    private record Guard(
            CircuitBreaker circuitBreaker,
            Retry retry,
            Bulkhead bulkhead,
            Counter bulkheadRejected,
            Counter circuitRejected) {
    }
}
