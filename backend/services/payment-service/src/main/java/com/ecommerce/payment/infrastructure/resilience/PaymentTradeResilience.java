package com.ecommerce.payment.infrastructure.resilience;

import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.infrastructure.config.PaymentClientProperties;
import com.ecommerce.payment.infrastructure.config.TradePaymentContextResilienceProperties;
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
import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class PaymentTradeResilience {

    public static final String INSTANCE_NAME = "paymentTradePaymentContext";

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final Counter bulkheadRejected;
    private final Counter circuitRejected;

    public PaymentTradeResilience(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry,
            MeterRegistry meterRegistry,
            PaymentClientProperties clientProperties,
            TradePaymentContextResilienceProperties properties) {
        Objects.requireNonNull(circuitBreakerRegistry, "circuitBreakerRegistry");
        Objects.requireNonNull(retryRegistry, "retryRegistry");
        Objects.requireNonNull(bulkheadRegistry, "bulkheadRegistry");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        validateBudget(clientProperties, properties);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .waitDuration(properties.retryWait())
                .retryOnException(PaymentTradeResilience::isRetryable)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .recordException(PaymentTradeResilience::isRecordable)
                .ignoreException(PaymentTradeResilience::isIgnored)
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(properties.bulkheadMaxWait())
                .build();

        retry = retryRegistry.retry(INSTANCE_NAME, retryConfig);
        circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME, circuitBreakerConfig);
        bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME, bulkheadConfig);
        bulkheadRejected = rejectionCounter(meterRegistry, "bulkhead");
        circuitRejected = rejectionCounter(meterRegistry, "circuit");
    }

    public <T> T execute(Supplier<T> remoteCall) {
        Objects.requireNonNull(remoteCall, "remoteCall");
        SynchronousBoundaryGuard.requireOutsideTransaction(INSTANCE_NAME);
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead, remoteCall);
        guarded = Retry.decorateSupplier(retry, guarded);
        guarded = CircuitBreaker.decorateSupplier(circuitBreaker, guarded);
        try {
            return guarded.get();
        } catch (PaymentException exception) {
            throw exception;
        } catch (BulkheadFullException exception) {
            bulkheadRejected.increment();
            throw new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        } catch (CallNotPermittedException exception) {
            circuitRejected.increment();
            throw new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        } catch (RuntimeException exception) {
            throw new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    private static boolean isRetryable(Throwable throwable) {
        return throwable instanceof RemoteDependencyFailure failure && failure.retryable();
    }

    private static boolean isRecordable(Throwable throwable) {
        return throwable instanceof RemoteDependencyFailure failure && failure.recordable();
    }

    private static boolean isIgnored(Throwable throwable) {
        return throwable instanceof BulkheadFullException
                || throwable instanceof RemoteDependencyFailure failure && !failure.recordable();
    }

    private static void validateBudget(
            PaymentClientProperties clientProperties,
            TradePaymentContextResilienceProperties properties) {
        Duration maximumAttemptTime = clientProperties.connectTimeout().plus(clientProperties.readTimeout());
        Duration maximumRetryWait = properties.retryWait().multipliedBy(properties.maxAttempts() - 1L);
        Duration configuredWorstCase = maximumAttemptTime
                .multipliedBy(properties.maxAttempts())
                .plus(maximumRetryWait)
                .plus(properties.bulkheadMaxWait().multipliedBy(properties.maxAttempts()));
        if (configuredWorstCase.compareTo(properties.totalBudget()) > 0) {
            throw new IllegalArgumentException(
                    "Payment to Trade timeout/retry policy can exceed its total budget: worstCase="
                            + configuredWorstCase + ", totalBudget=" + properties.totalBudget());
        }
    }

    private static Counter rejectionCounter(MeterRegistry meterRegistry, String guard) {
        return Counter.builder("ecommerce.http.client.resilience.rejections")
                .description("Synchronous dependency calls rejected before starting remote I/O")
                .tag("service", "payment-service")
                .tag("dependency", "trade-service")
                .tag("operation", "payment_context")
                .tag("guard", guard)
                .register(meterRegistry);
    }
}
