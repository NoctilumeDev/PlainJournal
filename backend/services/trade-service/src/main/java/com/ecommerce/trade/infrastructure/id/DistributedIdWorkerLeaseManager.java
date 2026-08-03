package com.ecommerce.trade.infrastructure.id;

import com.ecommerce.trade.infrastructure.config.DistributedIdProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class DistributedIdWorkerLeaseManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DistributedIdWorkerLeaseManager.class);
    private static final long NO_ACTIVE_LEASE = Long.MIN_VALUE;

    private final DistributedIdWorkerLeaseStore store;
    private final DistributedIdProperties properties;
    private final LongSupplier monotonicClock;
    private final long ownershipSafetyWindowNanos;
    private final int workerId;
    private final String owner = UUID.randomUUID().toString().replace("-", "");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "trade-distributed-id-lease");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong ownershipSafeUntilNanos = new AtomicLong(NO_ACTIVE_LEASE);

    public DistributedIdWorkerLeaseManager(
            DistributedIdWorkerLeaseStore store,
            DistributedIdProperties properties) {
        this(store, properties, System::nanoTime);
    }

    DistributedIdWorkerLeaseManager(
            DistributedIdWorkerLeaseStore store,
            DistributedIdProperties properties,
            LongSupplier monotonicClock) {
        this.store = store;
        this.properties = properties;
        this.monotonicClock = monotonicClock;
        Duration safetyWindow = properties.leaseDuration()
                .minus(properties.renewalInterval());
        this.ownershipSafetyWindowNanos = safetyWindow.toNanos();
        this.workerId = properties.resolvedWorkerId();
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            running.set(true);
            return;
        }
        long acquisitionStartedAt = monotonicClock.getAsLong();
        Instant now = store.currentTime();
        Instant acquiredUntil = now.plus(properties.leaseDuration());
        if (!store.tryAcquire(properties.namespace(), workerId, owner, now, acquiredUntil)) {
            throw new IllegalStateException(
                    "distributed ID worker is already leased: namespace="
                            + properties.namespace() + ", workerId=" + workerId);
        }
        ownershipSafeUntilNanos.set(
                acquisitionStartedAt + ownershipSafetyWindowNanos);
        running.set(true);
        scheduler.scheduleWithFixedDelay(
                this::renewSafely,
                properties.renewalInterval().toMillis(),
                properties.renewalInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Distributed ID worker lease acquired: namespace={}, workerId={}, instanceId={}",
                properties.namespace(), workerId, properties.instanceId());
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        boolean wasRunning = running.getAndSet(false);
        ownershipSafeUntilNanos.set(NO_ACTIVE_LEASE);
        scheduler.shutdownNow();
        if (wasRunning && properties.enabled()) {
            store.release(properties.namespace(), workerId, owner);
            log.info("Distributed ID worker lease released: namespace={}, workerId={}",
                    properties.namespace(), workerId);
        }
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public boolean isOwned() {
        if (!running.get()) {
            return false;
        }
        if (!properties.enabled()) {
            return true;
        }
        long validUntil = ownershipSafeUntilNanos.get();
        return validUntil != NO_ACTIVE_LEASE
                && validUntil - monotonicClock.getAsLong() > 0;
    }

    public int workerId() {
        return workerId;
    }

    public String namespace() {
        return properties.namespace();
    }

    private void renewSafely() {
        if (!isOwned()) {
            running.set(false);
            ownershipSafeUntilNanos.set(NO_ACTIVE_LEASE);
            return;
        }
        try {
            long renewalStartedAt = monotonicClock.getAsLong();
            Instant now = store.currentTime();
            Instant renewedUntil = now.plus(properties.leaseDuration());
            boolean renewed = store.renew(properties.namespace(), workerId, owner, now,
                    renewedUntil);
            if (!renewed) {
                running.set(false);
                ownershipSafeUntilNanos.set(NO_ACTIVE_LEASE);
                log.error("Distributed ID worker lease was lost: namespace={}, workerId={}",
                        properties.namespace(), workerId);
            } else {
                ownershipSafeUntilNanos.set(
                        renewalStartedAt + ownershipSafetyWindowNanos);
            }
        } catch (RuntimeException exception) {
            running.set(false);
            ownershipSafeUntilNanos.set(NO_ACTIVE_LEASE);
            log.error("Distributed ID worker lease renewal failed: namespace={}, workerId={}",
                    properties.namespace(), workerId, exception);
        }
    }
}
