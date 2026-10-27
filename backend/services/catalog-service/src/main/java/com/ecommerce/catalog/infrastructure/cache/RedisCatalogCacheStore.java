package com.ecommerce.catalog.infrastructure.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class RedisCatalogCacheStore implements CatalogCacheStore {

    private static final DefaultRedisScript<Long> PUT_IF_OWNED_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCatalogCacheStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public boolean putIfOwned(String key, String value, Duration ttl, String lockKey, String token) {
        return Long.valueOf(1L).equals(redisTemplate.execute(
                PUT_IF_OWNED_SCRIPT, List.of(lockKey, key), token, value,
                Long.toString(Math.max(1L, ttl.toMillis()))));
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean tryLock(String key, String token, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl));
    }

    @Override
    public void unlock(String key, String token) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
    }

    @Override
    public void publish(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
    }
}
