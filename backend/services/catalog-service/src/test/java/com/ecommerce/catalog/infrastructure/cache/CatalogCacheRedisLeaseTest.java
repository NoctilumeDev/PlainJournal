package com.ecommerce.catalog.infrastructure.cache;

import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Redis and real caches with controlled database snapshots; no live catalog data. */
@EnabledIfSystemProperty(named = "catalog.redis.test.port", matches = "[0-9]+")
class CatalogCacheRedisLeaseTest {

    @Test
    void oldUnlockDoesNotDeleteSuccessor() throws Exception {
        try (OwnedRedis connection = connection()) {
            var redis = new StringRedisTemplate(connection);
            var store = new RedisCatalogCacheStore(redis);
            String key = "catalog-lease-test:" + UUID.randomUUID();
            try {
                assertTrue(store.tryLock(key, "old", Duration.ofMillis(150)));
                awaitExpiry(redis, key);
                assertTrue(store.tryLock(key, "new", Duration.ofSeconds(5)));
                store.unlock(key, "old");
                assertEquals("new", redis.opsForValue().get(key));
            } finally {
                redis.delete(key);
            }
        }
    }

    @Test
    void atomicWriteRequiresCurrentOwnerAndSetsExpiry() throws Exception {
        try (OwnedRedis connection = connection()) {
            var redis = new StringRedisTemplate(connection);
            var store = new RedisCatalogCacheStore(redis);
            String key = "catalog-lease-test:" + UUID.randomUUID();
            String lock = key + ":lock";
            try {
                assertFalse(store.putIfOwned(key, "unowned", Duration.ofSeconds(30), lock, "old"));
                assertTrue(store.tryLock(lock, "old", Duration.ofSeconds(5)));
                assertTrue(store.putIfOwned(key, "current", Duration.ofSeconds(30), lock, "old"));
                assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 0);
                assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) <= 30000);
                assertFalse(store.putIfOwned(key, "wrong", Duration.ofSeconds(30), lock, "other"));
                assertEquals("current", redis.opsForValue().get(key));
                store.unlock(lock, "old");
                assertTrue(store.tryLock(lock, "expired", Duration.ofMillis(150)));
                awaitExpiry(redis, lock);
                assertFalse(store.putIfOwned(key, "stale", Duration.ofSeconds(30), lock, "expired"));
                assertTrue(store.tryLock(lock, "successor", Duration.ofSeconds(5)));
                assertTrue(store.putIfOwned(key, "new", Duration.ofSeconds(30), lock, "successor"));
                assertFalse(store.putIfOwned(key, "stale", Duration.ofSeconds(30), lock, "expired"));
                store.unlock(lock, "expired");
                assertEquals("successor", redis.opsForValue().get(lock));
                assertEquals("new", redis.opsForValue().get(key));
            } finally {
                redis.delete(List.of(key, lock));
            }
        }
    }

    @Test
    void receivedInvalidationStopsExpiredOldLoader() throws Exception {
        scenario(true, false);
    }

    @Test
    void missedInvalidationStillRejectsExpiredOldLoader() throws Exception {
        scenario(false, false);
    }

    @Test
    void expiredNegativeLoadCannotHideNewProduct() throws Exception {
        scenario(false, true);
    }

    private static void scenario(boolean deliverInvalidation, boolean negative) throws Exception {
        try (OwnedRedis connection = connection()) {
            var redis = new StringRedisTemplate(connection);
            var store = new RedisCatalogCacheStore(redis);
            String namespace = "lease-test-" + UUID.randomUUID();
            String key = "ecommerce:" + namespace + ":catalog:product-detail:v1:1";
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callers = Executors.newSingleThreadExecutor();
            var properties = new CatalogCacheProperties(true, namespace, 100,
                    Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1),
                    Duration.ofSeconds(10), 0, Duration.ofSeconds(3), 4, 1, 10,
                    Duration.ofMillis(150), namespace);
            try (var oldCache = cache(properties, store);
                 var successor = cache(properties, store);
                 var reader = cache(properties, store)) {
                var old = callers.submit(() -> oldCache.get(1L, () -> {
                    started.countDown();
                    await(release);
                    return negative ? Optional.empty() : Optional.of(product("old", 1));
                }));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                awaitExpiry(redis, key + ":rebuild-lock");
                // DB mutation and Pub/Sub delivery are independent, controlled inputs.
                if (deliverInvalidation) {
                    oldCache.receiveInvalidation(1L);
                }
                assertEquals("new", successor.get(1L, () -> Optional.of(product("new", 2)))
                        .orElseThrow().title());
                release.countDown();
                Optional<ProductDetail> snapshot = old.get(3, TimeUnit.SECONDS);
                if (negative) {
                    assertTrue(snapshot.isEmpty());
                } else {
                    assertEquals("old", snapshot.orElseThrow().title());
                }
                assertEquals("new", reader.get(1L, () -> {
                    throw new AssertionError("must inspect the shared cache, not load from DB");
                }).orElseThrow().title());
                assertTrue(oldCache.localRecord(1L).isEmpty(),
                        "expired loader must not retain its snapshot locally");
            } finally {
                release.countDown();
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
                redis.delete(List.of(key, key + ":rebuild-lock"));
            }
        }
    }

    private static final class OwnedRedis extends LettuceConnectionFactory implements AutoCloseable {
        private OwnedRedis() {
            super("127.0.0.1", Integer.parseInt(System.getProperty("catalog.redis.test.port")));
        }

        @Override
        public void close() {
            destroy();
        }
    }

    private static OwnedRedis connection() {
        var connection = new OwnedRedis();
        connection.afterPropertiesSet();
        connection.start();
        return connection;
    }

    private static void awaitExpiry(StringRedisTemplate redis, String key) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)), "test requires actual Redis lease expiry");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test snapshot release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static ProductDetail product(String title, int version) {
        return new ProductDetail(1L, title, "subtitle", "description", "ACTIVE", version,
                null, null, List.of(), List.of());
    }

    private static TwoLevelProductDetailCache cache(CatalogCacheProperties properties, CatalogCacheStore store) {
        TransactionOperations transaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        return new TwoLevelProductDetailCache(properties, store,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), new SimpleMeterRegistry(),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10)), transaction);
    }
}
