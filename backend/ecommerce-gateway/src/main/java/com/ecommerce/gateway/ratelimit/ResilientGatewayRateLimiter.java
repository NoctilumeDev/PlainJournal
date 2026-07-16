package com.ecommerce.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ResilientGatewayRateLimiter implements GatewayRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ResilientGatewayRateLimiter.class);
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local requests = redis.call('INCR', KEYS[1])
            if requests == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if requests > tonumber(ARGV[2]) then
                return 0
            end
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayRateLimitProperties properties;
    private final Cache<String, LocalWindow> localWindows;
    private final AtomicBoolean redisDegraded = new AtomicBoolean(false);

    public ResilientGatewayRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.localWindows = Caffeine.newBuilder()
                .maximumSize(properties.localMaximumSize())
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    @Override
    public Mono<Boolean> isAllowed(
            String policyName,
            String clientIdentifier,
            GatewayRateLimitProperties.Policy policy,
            Instant now) {
        String subjectHash = hash(clientIdentifier);
        String key = "ecommerce:" + properties.namespace() + ":gateway:rate:"
                + policyName + ":" + subjectHash;
        boolean locallyAllowed = recordLocalRequest(key, policy, now);
        if (!properties.redisEnabled()) {
            return Mono.just(locallyAllowed);
        }

        return redisTemplate.execute(
                        RATE_LIMIT_SCRIPT,
                        List.of(key),
                        List.of(Long.toString(policy.window().toMillis()), Integer.toString(policy.limit()))
                )
                .next()
                .map(result -> locallyAllowed && result != null && result == 1L)
                .doOnNext(ignored -> markRedisHealthy())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    markRedisDegraded(new IllegalStateException("Redis rate-limit script returned no result"));
                    return locallyAllowed;
                }))
                .timeout(properties.redisTimeout())
                .onErrorResume(exception -> {
                    markRedisDegraded(exception);
                    return Mono.just(locallyAllowed);
                });
    }

    private boolean recordLocalRequest(
            String key,
            GatewayRateLimitProperties.Policy policy,
            Instant now) {
        AtomicBoolean allowed = new AtomicBoolean();
        localWindows.asMap().compute(key, (ignored, current) -> {
            if (current == null || !current.resetAt().isAfter(now)) {
                allowed.set(true);
                return new LocalWindow(1, now.plus(policy.window()));
            }
            int requests = current.requests() + 1;
            allowed.set(requests <= policy.limit());
            return new LocalWindow(requests, current.resetAt());
        });
        return allowed.get();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void markRedisDegraded(Throwable exception) {
        if (redisDegraded.compareAndSet(false, true)) {
            log.warn("Redis gateway rate limiting is unavailable; using bounded local fallback", exception);
        }
    }

    private void markRedisHealthy() {
        if (redisDegraded.compareAndSet(true, false)) {
            log.info("Redis gateway rate limiting recovered");
        }
    }

    private record LocalWindow(int requests, Instant resetAt) {
    }
}
