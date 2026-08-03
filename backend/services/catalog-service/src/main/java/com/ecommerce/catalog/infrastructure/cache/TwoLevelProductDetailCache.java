package com.ecommerce.catalog.infrastructure.cache;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.CatalogModels.MediaView;
import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.port.ProductDetailCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class TwoLevelProductDetailCache implements ProductDetailCache, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelProductDetailCache.class);
    private static final int SCHEMA_VERSION = 2;

    private final CatalogCacheProperties properties;
    private final CatalogCacheStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionOperations readTransaction;
    private final Cache<Long, CacheEnvelope> localCache;
    private final ConcurrentHashMap<Long, CompletableFuture<CacheEnvelope>> inFlight =
            new ConcurrentHashMap<>();
    private final Semaphore rebuildPermits;
    private final ExecutorService refreshExecutor;
    private final Counter localHits;
    private final Counter localMisses;
    private final Counter redisHits;
    private final Counter redisMisses;
    private final Counter databaseLoads;
    private final Counter redisFailures;
    private final Counter staleResponses;
    private final Counter rebuildRejections;
    private final Counter invalidations;

    public TwoLevelProductDetailCache(
            CatalogCacheProperties properties,
            CatalogCacheStore store,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry,
            ThreadPoolExecutor refreshExecutor,
            TransactionOperations readTransaction) {
        this.properties = properties;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.readTransaction = readTransaction;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.localMaximumSize())
                .expireAfterWrite(properties.localTtl())
                .build();
        this.rebuildPermits = new Semaphore(properties.rebuildMaxConcurrent());
        this.refreshExecutor = refreshExecutor;
        this.localHits = accessCounter(meterRegistry, "local", "hit");
        this.localMisses = accessCounter(meterRegistry, "local", "miss");
        this.redisHits = accessCounter(meterRegistry, "redis", "hit");
        this.redisMisses = accessCounter(meterRegistry, "redis", "miss");
        this.databaseLoads = Counter.builder("ecommerce.catalog.cache.database.loads")
                .description("Catalog product detail loads reaching MySQL")
                .register(meterRegistry);
        this.redisFailures = Counter.builder("ecommerce.catalog.cache.redis.failures")
                .description("Catalog cache Redis operations that failed and degraded locally")
                .register(meterRegistry);
        this.staleResponses = Counter.builder("ecommerce.catalog.cache.stale.responses")
                .description("Catalog product detail responses served from logically stale cache data")
                .register(meterRegistry);
        this.rebuildRejections = Counter.builder("ecommerce.catalog.cache.rebuild.rejections")
                .description("Catalog cache rebuilds rejected by bounded capacity protection")
                .register(meterRegistry);
        this.invalidations = Counter.builder("ecommerce.catalog.cache.invalidations")
                .description("Catalog product detail cache invalidations")
                .register(meterRegistry);
    }

    @Override
    public Optional<ProductDetail> get(
            Long productId,
            Supplier<Optional<ProductDetail>> loader) {
        CacheEnvelope local = localCache.getIfPresent(productId);
        if (local != null && !local.hardExpired(now())) {
            localHits.increment();
            return resolve(productId, local, loader);
        }
        localMisses.increment();

        Optional<CacheEnvelope> distributed = readDistributed(productId);
        if (distributed.isPresent()) {
            CacheEnvelope envelope = distributed.orElseThrow();
            localCache.put(productId, envelope);
            return resolve(productId, envelope, loader);
        }
        return loadCold(productId, loader);
    }

    @Override
    public void invalidateAfterCommit(Long productId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            invalidate(productId, true);
                        }
                    });
            return;
        }
        invalidate(productId, true);
    }

    @Override
    public void receiveInvalidation(Long productId) {
        localCache.invalidate(productId);
        invalidations.increment();
    }

    Optional<CacheEnvelope> localRecord(Long productId) {
        return Optional.ofNullable(localCache.getIfPresent(productId));
    }

    private Optional<ProductDetail> resolve(
            Long productId,
            CacheEnvelope envelope,
            Supplier<Optional<ProductDetail>> loader) {
        if (envelope.negative()) {
            return Optional.empty();
        }
        if (envelope.softExpired(now())) {
            staleResponses.increment();
            refreshAsync(productId, envelope, loader);
        }
        return Optional.of(envelope.product());
    }

    private Optional<ProductDetail> loadCold(
            Long productId,
            Supplier<Optional<ProductDetail>> loader) {
        CompletableFuture<CacheEnvelope> future = new CompletableFuture<>();
        CompletableFuture<CacheEnvelope> existing = inFlight.putIfAbsent(productId, future);
        if (existing != null) {
            return await(existing);
        }

        try {
            if (!tryAcquirePermit()) {
                rebuildRejections.increment();
                throw new CatalogException(CatalogError.CAPACITY_PROTECTION);
            }
            try {
                CacheEnvelope local = localCache.getIfPresent(productId);
                if (local != null && !local.hardExpired(now())) {
                    future.complete(local);
                    return resolve(productId, local, loader);
                }
                Optional<CacheEnvelope> distributed = readDistributed(productId);
                if (distributed.isPresent()) {
                    CacheEnvelope record = distributed.orElseThrow();
                    localCache.put(productId, record);
                    future.complete(record);
                    return resolve(productId, record, loader);
                }
                CacheEnvelope loaded = rebuild(productId, loader, true, null);
                future.complete(loaded);
                return loaded.negative() ? Optional.empty() : Optional.of(loaded.product());
            } finally {
                rebuildPermits.release();
            }
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(productId, future);
        }
    }

    private void refreshAsync(
            Long productId,
            CacheEnvelope staleFallback,
            Supplier<Optional<ProductDetail>> loader) {
        CompletableFuture<CacheEnvelope> future = new CompletableFuture<>();
        if (inFlight.putIfAbsent(productId, future) != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    if (!tryAcquirePermit()) {
                        rebuildRejections.increment();
                        future.complete(staleFallback);
                        return;
                    }
                    try {
                        CacheEnvelope refreshed =
                                rebuild(productId, loader, false, staleFallback);
                        future.complete(refreshed);
                    } finally {
                        rebuildPermits.release();
                    }
                } catch (RuntimeException exception) {
                    future.completeExceptionally(exception);
                    log.debug("Catalog cache asynchronous refresh failed: productId={}",
                            productId, exception);
                } finally {
                    inFlight.remove(productId, future);
                }
            });
        } catch (RuntimeException exception) {
            inFlight.remove(productId, future);
            future.complete(staleFallback);
            rebuildRejections.increment();
            log.debug("Catalog cache refresh queue is full: productId={}", productId);
        }
    }

    private CacheEnvelope rebuild(
            Long productId,
            Supplier<Optional<ProductDetail>> loader,
            boolean allowUnlockedFallback,
            CacheEnvelope staleFallback) {
        String token = UUID.randomUUID().toString();
        LockAttempt lockAttempt = tryDistributedLock(productId, token);
        if (lockAttempt == LockAttempt.CONTENDED) {
            Optional<CacheEnvelope> shared = awaitDistributed(productId);
            if (shared.isPresent()) {
                CacheEnvelope record = shared.orElseThrow();
                localCache.put(productId, record);
                return record;
            }
            if (allowUnlockedFallback) {
                rebuildRejections.increment();
                throw new CatalogException(CatalogError.CAPACITY_PROTECTION);
            }
        }
        if (lockAttempt == LockAttempt.CONTENDED && !allowUnlockedFallback) {
            return staleFallback;
        }
        boolean locked = lockAttempt == LockAttempt.ACQUIRED;
        try {
            if (locked) {
                Optional<CacheEnvelope> existing = readDistributed(productId);
                if (existing.isPresent() && !existing.orElseThrow().softExpired(now())) {
                    CacheEnvelope record = existing.orElseThrow();
                    localCache.put(productId, record);
                    return record;
                }
            }
            databaseLoads.increment();
            Optional<ProductDetail> databaseValue = readTransaction.execute(status -> loader.get());
            CacheEnvelope loaded = Optional.ofNullable(databaseValue)
                    .orElseGet(Optional::empty)
                    .map(this::valueEnvelope)
                    .orElseGet(this::negativeEnvelope);
            localCache.put(productId, loaded);
            writeDistributed(productId, loaded);
            return loaded;
        } finally {
            if (locked) {
                unlock(productId, token);
            }
        }
    }

    private Optional<CacheEnvelope> awaitDistributed(Long productId) {
        long deadline = System.nanoTime() + properties.rebuildWait().toNanos();
        do {
            Optional<CacheEnvelope> shared = readDistributed(productId);
            if (shared.isPresent() && !shared.orElseThrow().softExpired(now())) {
                return shared;
            }
            if (properties.rebuildWait().isZero()) {
                break;
            }
            try {
                Thread.sleep(Math.min(10L, Math.max(1L, properties.rebuildWait().toMillis())));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    private Optional<ProductDetail> await(CompletableFuture<CacheEnvelope> future) {
        try {
            CacheEnvelope record = future.get(
                    Math.max(1L, properties.rebuildWait().toMillis()), TimeUnit.MILLISECONDS);
            return record.negative() ? Optional.empty() : Optional.of(record.product());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CatalogException(CatalogError.CAPACITY_PROTECTION);
        } catch (ExecutionException | TimeoutException | CancellationException exception) {
            rebuildRejections.increment();
            throw new CatalogException(CatalogError.CAPACITY_PROTECTION);
        }
    }

    private boolean tryAcquirePermit() {
        try {
            return rebuildPermits.tryAcquire(
                    properties.rebuildWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<CacheEnvelope> readDistributed(Long productId) {
        try {
            Optional<String> value = store.get(cacheKey(productId));
            if (value.isEmpty()) {
                redisMisses.increment();
                return Optional.empty();
            }
            CacheEnvelope envelope = objectMapper.readValue(value.orElseThrow(), CacheEnvelope.class);
            if (envelope.schemaVersion() != SCHEMA_VERSION || envelope.hardExpired(now())) {
                store.delete(cacheKey(productId));
                redisMisses.increment();
                return Optional.empty();
            }
            redisHits.increment();
            return Optional.of(envelope);
        } catch (JsonProcessingException exception) {
            redisFailures.increment();
            safeDelete(cacheKey(productId));
            return Optional.empty();
        } catch (RuntimeException exception) {
            redisFailures.increment();
            return Optional.empty();
        }
    }

    private void writeDistributed(Long productId, CacheEnvelope envelope) {
        try {
            long remainingMillis = Math.max(1L, envelope.hardExpiresAtEpochMs() - now());
            store.put(
                    cacheKey(productId),
                    objectMapper.writeValueAsString(envelope),
                    Duration.ofMillis(remainingMillis));
        } catch (JsonProcessingException exception) {
            redisFailures.increment();
            log.warn("Catalog cache record serialization failed; serving from MySQL/local cache: productId={}",
                    productId, exception);
        } catch (RuntimeException exception) {
            redisFailures.increment();
        }
    }

    private LockAttempt tryDistributedLock(Long productId, String token) {
        try {
            return store.tryLock(lockKey(productId), token, properties.distributedLockTtl())
                    ? LockAttempt.ACQUIRED
                    : LockAttempt.CONTENDED;
        } catch (RuntimeException exception) {
            redisFailures.increment();
            return LockAttempt.UNAVAILABLE;
        }
    }

    private void unlock(Long productId, String token) {
        try {
            store.unlock(lockKey(productId), token);
        } catch (RuntimeException exception) {
            redisFailures.increment();
        }
    }

    private void invalidate(Long productId, boolean publish) {
        localCache.invalidate(productId);
        safeDelete(cacheKey(productId));
        if (publish) {
            try {
                store.publish(properties.invalidationChannel(), productId.toString());
            } catch (RuntimeException exception) {
                redisFailures.increment();
            }
        }
        invalidations.increment();
    }

    private void safeDelete(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException exception) {
            redisFailures.increment();
        }
    }

    private CacheEnvelope valueEnvelope(ProductDetail product) {
        long now = now();
        long freshMillis = jitter(properties.freshTtl()).toMillis();
        long staleMillis = properties.staleTtl().toMillis();
        return new CacheEnvelope(
                SCHEMA_VERSION,
                false,
                stableProduct(product),
                now + freshMillis,
                now + freshMillis + staleMillis);
    }

    private ProductDetail stableProduct(ProductDetail product) {
        return new ProductDetail(
                product.id(),
                product.title(),
                product.subtitle(),
                product.description(),
                product.status(),
                product.version(),
                product.category(),
                product.brand(),
                product.skus(),
                product.media().stream()
                        .map(media -> new MediaView(
                                media.id(),
                                media.skuId(),
                                media.objectKey(),
                                media.mimeType(),
                                media.sizeBytes(),
                                media.sortOrder(),
                                null))
                        .toList());
    }

    private CacheEnvelope negativeEnvelope() {
        long now = now();
        long ttlMillis = jitter(properties.negativeTtl()).toMillis();
        return new CacheEnvelope(
                SCHEMA_VERSION,
                true,
                null,
                now + ttlMillis,
                now + ttlMillis);
    }

    private Duration jitter(Duration base) {
        if (properties.ttlJitter() == 0) {
            return base;
        }
        double factor = 1 + ThreadLocalRandom.current().nextDouble(
                -properties.ttlJitter(), properties.ttlJitter());
        return Duration.ofMillis(Math.max(1L, Math.round(base.toMillis() * factor)));
    }

    private String cacheKey(Long productId) {
        return "ecommerce:" + properties.namespace() + ":catalog:product-detail:v1:" + productId;
    }

    private String lockKey(Long productId) {
        return cacheKey(productId) + ":rebuild-lock";
    }

    private long now() {
        return clock.millis();
    }

    @Override
    public void close() {
        refreshExecutor.shutdown();
    }

    private static Counter accessCounter(
            MeterRegistry registry,
            String layer,
            String outcome) {
        return Counter.builder("ecommerce.catalog.cache.accesses")
                .description("Catalog product detail cache accesses")
                .tag("layer", layer)
                .tag("outcome", outcome)
                .register(registry);
    }

    public record CacheEnvelope(
            int schemaVersion,
            boolean negative,
            ProductDetail product,
            long softExpiresAtEpochMs,
            long hardExpiresAtEpochMs
    ) {

        boolean softExpired(long now) {
            return now >= softExpiresAtEpochMs;
        }

        boolean hardExpired(long now) {
            return now >= hardExpiresAtEpochMs;
        }
    }

    private enum LockAttempt {
        ACQUIRED,
        CONTENDED,
        UNAVAILABLE
    }
}
