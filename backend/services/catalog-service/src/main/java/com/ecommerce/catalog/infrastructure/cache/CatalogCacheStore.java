package com.ecommerce.catalog.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

public interface CatalogCacheStore {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    void delete(String key);

    boolean tryLock(String key, String token, Duration ttl);

    void unlock(String key, String token);

    void publish(String channel, String message);
}
