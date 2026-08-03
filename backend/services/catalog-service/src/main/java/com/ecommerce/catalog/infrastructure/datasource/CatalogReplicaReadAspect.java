package com.ecommerce.catalog.infrastructure.datasource;

import com.ecommerce.catalog.application.routing.CatalogReplicaRead;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
final class CatalogReplicaReadAspect {

    private static final Logger log = LoggerFactory.getLogger(CatalogReplicaReadAspect.class);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final CatalogReadReplicaProperties properties;
    private final CatalogDataSourceMetrics metrics;
    private final AtomicLong nextWarningNanos = new AtomicLong();

    CatalogReplicaReadAspect(
            CatalogReadReplicaProperties properties,
            CatalogDataSourceMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Around("@annotation(replicaRead)")
    Object routeRead(
            ProceedingJoinPoint joinPoint,
            CatalogReplicaRead replicaRead) throws Throwable {
        Throwable replicaFailure;
        boolean attemptedReplica;
        try (CatalogReadRouteContext.Scope ignored = CatalogReadRouteContext.preferReplica()) {
            attemptedReplica = CatalogReadRouteContext.shouldUseReplica();
            try {
                return joinPoint.proceed();
            } catch (Throwable failure) {
                replicaFailure = failure;
            }
        }

        if (!attemptedReplica
                || !properties.isEnabled()
                || !properties.isFallbackToPrimary()
                || !CatalogReplicaFailureClassifier.isConnectionFailure(replicaFailure)) {
            throw replicaFailure;
        }

        metrics.recordReplicaFallback();
        logFallback(replicaFailure);
        try (CatalogReadRouteContext.Scope ignored = CatalogReadRouteContext.forcePrimary()) {
            try {
                return joinPoint.proceed();
            } catch (Throwable primaryFailure) {
                primaryFailure.addSuppressed(replicaFailure);
                throw primaryFailure;
            }
        }
    }

    private void logFallback(Throwable failure) {
        long now = System.nanoTime();
        long next = nextWarningNanos.get();
        if (now >= next && nextWarningNanos.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            log.warn("Catalog replica read failed; replaying once on the primary: {}",
                    failure.toString());
        }
    }
}
