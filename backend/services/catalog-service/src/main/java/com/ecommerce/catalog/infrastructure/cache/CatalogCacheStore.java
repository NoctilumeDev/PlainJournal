package com.ecommerce.catalog.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

public interface CatalogCacheStore {

    Optional<String> get(String key);

    // Ownership check and cache write must be a single atomic operation.
    boolean putIfOwned(String key, String value, Duration ttl, String lockKey, String token);

    void delete(String key);

    boolean tryLock(String key, String token, Duration ttl);

    void unlock(String key, String token);

    void publish(String channel, String message);
}
