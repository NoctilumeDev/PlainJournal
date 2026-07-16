package com.ecommerce.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
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
                policy
        );
    }
}
