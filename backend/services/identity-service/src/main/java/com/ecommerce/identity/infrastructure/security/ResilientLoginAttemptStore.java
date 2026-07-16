package com.ecommerce.identity.infrastructure.security;

import com.ecommerce.identity.application.port.LoginAttemptStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ResilientLoginAttemptStore implements LoginAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(ResilientLoginAttemptStore.class);
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return tonumber(ARGV[3])
            end
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if failures >= tonumber(ARGV[3]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                redis.call('DEL', KEYS[1])
            end
            return failures
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginAttemptProperties properties;
    private final Cache<String, LocalAttempt> localAttempts;
    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);

    public ResilientLoginAttemptStore(
            StringRedisTemplate redisTemplate,
            LoginAttemptProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.localAttempts = Caffeine.newBuilder()
                .maximumSize(properties.localMaximumSize())
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    public boolean isBlocked(String normalizedIdentifier, Instant now) {
        String identifierHash = hash(normalizedIdentifier);
        boolean locallyBlocked = isLocallyBlocked(identifierHash, now);
        if (!properties.redisEnabled()) {
            return locallyBlocked;
        }

        try {
            boolean redisBlocked = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(identifierHash)));
            markRedisHealthy();
            return locallyBlocked || redisBlocked;
        } catch (RuntimeException exception) {
            markRedisDegraded(exception);
            return locallyBlocked;
        }
    }

    @Override
    public FailureResult recordFailure(String normalizedIdentifier, Instant now) {
        String identifierHash = hash(normalizedIdentifier);
        FailureResult localResult = recordLocalFailure(identifierHash, now);
        if (!properties.redisEnabled()) {
            return localResult;
        }

        try {
            Long failures = redisTemplate.execute(
                    RECORD_FAILURE_SCRIPT,
                    List.of(failureKey(identifierHash), lockKey(identifierHash)),
                    Long.toString(properties.failureWindow().toMillis()),
                    Long.toString(properties.lockDuration().toMillis()),
                    Integer.toString(properties.maxFailures())
            );
            markRedisHealthy();
            int redisFailures = failures == null ? 0 : Math.toIntExact(failures);
            return new FailureResult(
                    Math.max(localResult.failureCount(), redisFailures),
                    localResult.blocked() || redisFailures >= properties.maxFailures()
            );
        } catch (RuntimeException exception) {
            markRedisDegraded(exception);
            return localResult;
        }
    }

    @Override
    public void clear(String normalizedIdentifier) {
        String identifierHash = hash(normalizedIdentifier);
        localAttempts.invalidate(identifierHash);
        if (!properties.redisEnabled()) {
            return;
        }

        try {
            redisTemplate.delete(List.of(failureKey(identifierHash), lockKey(identifierHash)));
            markRedisHealthy();
        } catch (RuntimeException exception) {
            markRedisDegraded(exception);
        }
    }

    private FailureResult recordLocalFailure(String identifierHash, Instant now) {
        AtomicReference<FailureResult> result = new AtomicReference<>();
        localAttempts.asMap().compute(identifierHash, (key, current) -> {
            if (current != null && current.blockedUntil() != null && current.blockedUntil().isAfter(now)) {
                result.set(new FailureResult(properties.maxFailures(), true));
                return current;
            }

            int failures = current == null || current.failureExpiresAt() == null
                    || !current.failureExpiresAt().isAfter(now)
                    ? 1
                    : current.failures() + 1;
            if (failures >= properties.maxFailures()) {
                result.set(new FailureResult(failures, true));
                return new LocalAttempt(0, null, now.plus(properties.lockDuration()));
            }

            result.set(new FailureResult(failures, false));
            return new LocalAttempt(failures, now.plus(properties.failureWindow()), null);
        });
        return result.get();
    }

    private boolean isLocallyBlocked(String identifierHash, Instant now) {
        LocalAttempt current = localAttempts.getIfPresent(identifierHash);
        if (current == null || current.blockedUntil() == null) {
            return false;
        }
        if (current.blockedUntil().isAfter(now)) {
            return true;
        }
        localAttempts.invalidate(identifierHash);
        return false;
    }

    private String failureKey(String identifierHash) {
        return keyPrefix() + "failures:" + identifierHash;
    }

    private String lockKey(String identifierHash) {
        return keyPrefix() + "lock:" + identifierHash;
    }

    private String keyPrefix() {
        return "ecommerce:" + properties.namespace() + ":identity:login:";
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void markRedisDegraded(RuntimeException exception) {
        if (redisDegraded.compareAndSet(false, true)) {
            log.warn("Redis login-attempt storage is unavailable; using bounded local fallback", exception);
        }
    }

    private void markRedisHealthy() {
        if (redisDegraded.compareAndSet(true, false)) {
            log.info("Redis login-attempt storage recovered");
        }
    }

    private record LocalAttempt(int failures, Instant failureExpiresAt, Instant blockedUntil) {
    }
}
