package com.ecommerce.catalog.infrastructure.cache;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.CatalogModels.BrandView;
import com.ecommerce.catalog.application.model.CatalogModels.CategoryView;
import com.ecommerce.catalog.application.model.CatalogModels.MediaView;
import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.model.CatalogModels.SkuView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoLevelProductDetailCacheTest {

    @Test
    void removesExpiringMediaUrlsFromCachedFacts() {
        FakeStore store = new FakeStore();
        CatalogCacheProperties properties = properties(Duration.ofMillis(100), 4);
        ProductDetail product = product("stable");
        ProductDetail withUrl = new ProductDetail(
                product.id(),
                product.title(),
                product.subtitle(),
                product.description(),
                product.status(),
                product.version(),
                product.category(),
                product.brand(),
                product.skus(),
                List.of(new MediaView(
                        40L,
                        null,
                        "products/1/cover.png",
                        "image/png",
                        128,
                        0,
                        "http://storage.invalid/expiring")));

        try (TwoLevelProductDetailCache cache =
                     cache(properties, store, new MutableClock())) {
            ProductDetail cached = cache.get(1L, () -> Optional.of(withUrl)).orElseThrow();

            assertThat(cached.media()).singleElement()
                    .satisfies(media -> {
                        assertThat(media.objectKey()).isEqualTo("products/1/cover.png");
                        assertThat(media.url()).isNull();
                    });
        }
    }

    @Test
    void coalescesConcurrentColdLoadsAcrossInstances() throws Exception {
        FakeStore store = new FakeStore();
        MutableClock clock = new MutableClock();
        CatalogCacheProperties properties = properties(Duration.ofSeconds(2), 4);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try (TwoLevelProductDetailCache first = cache(properties, store, clock);
             TwoLevelProductDetailCache second = cache(properties, store, clock)) {
            Future<Optional<ProductDetail>> firstResult = callers.submit(() ->
                    first.get(1L, () -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return Optional.of(product("first"));
                    }));
            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<Optional<ProductDetail>> secondResult = callers.submit(() ->
                    second.get(1L, () -> {
                        loads.incrementAndGet();
                        return Optional.of(product("duplicate"));
                    }));
            assertThat(store.lockContended.await(1, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();

            assertThat(firstResult.get(2, TimeUnit.SECONDS).orElseThrow().title())
                    .isEqualTo("first");
            assertThat(secondResult.get(2, TimeUnit.SECONDS).orElseThrow().title())
                    .isEqualTo("first");
            assertThat(loads).hasValue(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void cachesMissingProductsToPreventPenetration() {
        FakeStore store = new FakeStore();
        AtomicInteger loads = new AtomicInteger();

        try (TwoLevelProductDetailCache cache =
                     cache(properties(Duration.ofMillis(100), 4), store, new MutableClock())) {
            assertThat(cache.get(99L, () -> {
                loads.incrementAndGet();
                return Optional.empty();
            })).isEmpty();
            assertThat(cache.get(99L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("unexpected"));
            })).isEmpty();
            assertThat(loads).hasValue(1);
        }
    }

    @Test
    void servesStaleValueAndRefreshesOnBoundedExecutor() {
        FakeStore store = new FakeStore();
        MutableClock clock = new MutableClock();
        CatalogCacheProperties properties = new CatalogCacheProperties(
                true,
                "test",
                100,
                Duration.ofMinutes(1),
                Duration.ofMillis(10),
                Duration.ofMinutes(1),
                Duration.ofSeconds(10),
                0,
                Duration.ofSeconds(1),
                4,
                1,
                10,
                Duration.ofSeconds(2),
                "catalog-cache-test");
        AtomicInteger loads = new AtomicInteger();

        try (TwoLevelProductDetailCache cache = cache(properties, store, clock)) {
            ProductDetail first = cache.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("before"));
            }).orElseThrow();
            clock.advance(Duration.ofMillis(20));
            ProductDetail stale = cache.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("after"));
            }).orElseThrow();

            assertThat(first.title()).isEqualTo("before");
            assertThat(stale.title()).isEqualTo("before");
            awaitCondition(() -> cache.localRecord(1L)
                    .map(record -> "after".equals(record.product().title()))
                    .orElse(false));
            assertThat(loads).hasValue(2);
        }
    }

    @Test
    void waitsForFreshSharedValueWhenAnotherInstanceRefreshesAStaleRecord() throws Exception {
        FakeStore store = new FakeStore();
        MutableClock clock = new MutableClock();
        CatalogCacheProperties properties = properties(Duration.ofSeconds(2), 4);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        try (TwoLevelProductDetailCache first = cache(properties, store, clock);
             TwoLevelProductDetailCache second = cache(properties, store, clock)) {
            assertThat(first.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("before"));
            }).orElseThrow().title()).isEqualTo("before");
            assertThat(second.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("duplicate-cold-load"));
            }).orElseThrow().title()).isEqualTo("before");
            clock.advance(Duration.ofSeconds(90));

            assertThat(first.get(1L, () -> {
                loads.incrementAndGet();
                refreshStarted.countDown();
                await(releaseRefresh);
                return Optional.of(product("after"));
            }).orElseThrow().title()).isEqualTo("before");
            assertThat(refreshStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(second.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("duplicate-refresh"));
            }).orElseThrow().title()).isEqualTo("before");
            assertThat(store.lockContended.await(1, TimeUnit.SECONDS)).isTrue();
            releaseRefresh.countDown();

            awaitCondition(() -> first.localRecord(1L)
                    .map(record -> "after".equals(record.product().title()))
                    .orElse(false));
            awaitCondition(() -> second.localRecord(1L)
                    .map(record -> "after".equals(record.product().title()))
                    .orElse(false));
            assertThat(loads).hasValue(2);
        } finally {
            releaseRefresh.countDown();
        }
    }

    @Test
    void fallsBackToMysqlWhenRedisIsUnavailable() {
        FakeStore store = new FakeStore();
        store.fail = true;
        AtomicInteger loads = new AtomicInteger();

        try (TwoLevelProductDetailCache cache =
                     cache(properties(Duration.ofMillis(100), 4), store, new MutableClock())) {
            assertThat(cache.get(1L, () -> {
                loads.incrementAndGet();
                return Optional.of(product("database"));
            }).orElseThrow().title()).isEqualTo("database");
            assertThat(loads).hasValue(1);
        }
    }

    @Test
    void rejectsDistinctColdKeysWhenRebuildCapacityIsExhausted() throws Exception {
        FakeStore store = new FakeStore();
        CatalogCacheProperties properties = properties(Duration.ofMillis(20), 1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService callers = Executors.newSingleThreadExecutor();

        try (TwoLevelProductDetailCache cache = cache(properties, store, new MutableClock())) {
            Future<Optional<ProductDetail>> first = callers.submit(() ->
                    cache.get(1L, () -> {
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return Optional.of(product("first"));
                    }));
            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() ->
                    cache.get(2L, () -> Optional.of(product("second"))))
                    .isInstanceOfSatisfying(CatalogException.class, exception ->
                            assertThat(exception.error())
                                    .isEqualTo(CatalogError.CAPACITY_PROTECTION));

            releaseLoader.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isPresent();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void invalidationRemovesBothLevelsAndPublishesEvent() {
        FakeStore store = new FakeStore();

        try (TwoLevelProductDetailCache cache =
                     cache(properties(Duration.ofMillis(100), 4), store, new MutableClock())) {
            cache.get(1L, () -> Optional.of(product("before")));
            assertThat(store.values).isNotEmpty();
            assertThat(cache.localRecord(1L)).isPresent();

            cache.invalidateAfterCommit(1L);

            assertThat(store.values).isEmpty();
            assertThat(cache.localRecord(1L)).isEmpty();
            assertThat(store.lastPublished).isEqualTo("1");
        }
    }

    private static TwoLevelProductDetailCache cache(
            CatalogCacheProperties properties,
            FakeStore store,
            Clock clock) {
        return new TwoLevelProductDetailCache(
                properties,
                store,
                new ObjectMapper().findAndRegisterModules(),
                clock,
                new SimpleMeterRegistry(),
                refreshExecutor(properties),
                DIRECT_TRANSACTION);
    }

    private static final TransactionOperations DIRECT_TRANSACTION =
            new TransactionOperations() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    return action.doInTransaction(new SimpleTransactionStatus());
                }
            };

    private static ThreadPoolExecutor refreshExecutor(CatalogCacheProperties properties) {
        return new ThreadPoolExecutor(
                properties.refreshThreads(),
                properties.refreshThreads(),
                0,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(properties.refreshQueueCapacity()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static CatalogCacheProperties properties(Duration wait, int maximumConcurrent) {
        return new CatalogCacheProperties(
                true,
                "test",
                100,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofMinutes(1),
                Duration.ofSeconds(10),
                0,
                wait,
                maximumConcurrent,
                1,
                10,
                Duration.ofSeconds(2),
                "catalog-cache-test");
    }

    private static ProductDetail product(String title) {
        return new ProductDetail(
                1L,
                title,
                "subtitle",
                "description",
                "ACTIVE",
                1,
                new CategoryView(10L, null, "Category", "category", 1),
                new BrandView(20L, "Brand", "brand"),
                List.of(new SkuView(
                        30L,
                        "SKU-1",
                        "SKU",
                        "{}",
                        new BigDecimal("19.90"),
                        null,
                        "ACTIVE",
                        1)),
                List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Condition was not satisfied before the deadline");
    }

    private static final class FakeStore implements CatalogCacheStore {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, String> locks = new ConcurrentHashMap<>();
        private final CountDownLatch lockContended = new CountDownLatch(1);
        private volatile boolean fail;
        private volatile String lastPublished;

        @Override
        public Optional<String> get(String key) {
            requireAvailable();
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            requireAvailable();
            values.put(key, value);
        }

        @Override
        public void delete(String key) {
            requireAvailable();
            values.remove(key);
        }

        @Override
        public boolean tryLock(String key, String token, Duration ttl) {
            requireAvailable();
            boolean acquired = locks.putIfAbsent(key, token) == null;
            if (!acquired) {
                lockContended.countDown();
            }
            return acquired;
        }

        @Override
        public void unlock(String key, String token) {
            requireAvailable();
            locks.remove(key, token);
        }

        @Override
        public void publish(String channel, String message) {
            requireAvailable();
            lastPublished = message;
        }

        private void requireAvailable() {
            if (fail) {
                throw new IllegalStateException("Redis unavailable");
            }
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current = Instant.parse("2026-07-21T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
