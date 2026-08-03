package com.ecommerce.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;

class ResilientGatewayRateLimiterTest {

    @Test
    void enforcesBoundedLocalFallbackWhenRedisIsDisabled() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        GatewayRateLimitProperties properties = properties(false);
        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(redisTemplate, properties);
        GatewayRateLimitProperties.Policy policy = new GatewayRateLimitProperties.Policy(2, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-07-15T00:00:00Z");

        StepVerifier.create(rateLimiter.isAllowed("login", "127.0.0.1", policy, now))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("login", "127.0.0.1", policy, now.plusSeconds(1)))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("login", "127.0.0.1", policy, now.plusSeconds(2)))
                .expectNext(false)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("login", "127.0.0.1", policy, now.plusSeconds(61)))
                .expectNext(true)
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void doesNotIntersectHealthyRedisDecisionsWithAnIndependentLocalWindow() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyList())).thenReturn(Flux.just(1L));
        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(
                redisTemplate,
                properties(true));
        GatewayRateLimitProperties.Policy policy = new GatewayRateLimitProperties.Policy(1, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        StepVerifier.create(rateLimiter.isAllowed("flash-sale", "127.0.0.1", policy, now))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("flash-sale", "127.0.0.1", policy, now.plusMillis(1)))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void activatesTheBoundedLocalWindowOnlyWhenRedisFails() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis unavailable")));
        ResilientGatewayRateLimiter rateLimiter = new ResilientGatewayRateLimiter(
                redisTemplate,
                properties(true));
        GatewayRateLimitProperties.Policy policy = new GatewayRateLimitProperties.Policy(2, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        StepVerifier.create(rateLimiter.isAllowed("flash-sale", "127.0.0.1", policy, now))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("flash-sale", "127.0.0.1", policy, now.plusMillis(1)))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(rateLimiter.isAllowed("flash-sale", "127.0.0.1", policy, now.plusMillis(2)))
                .expectNext(false)
                .verifyComplete();
    }

    private GatewayRateLimitProperties properties(boolean redisEnabled) {
        GatewayRateLimitProperties.Policy policy = new GatewayRateLimitProperties.Policy(10, Duration.ofMinutes(1));
        return new GatewayRateLimitProperties(
                "test",
                true,
                redisEnabled,
                Duration.ofMillis(100),
                1000,
                policy,
                policy,
                policy,
                policy
        );
    }
}
