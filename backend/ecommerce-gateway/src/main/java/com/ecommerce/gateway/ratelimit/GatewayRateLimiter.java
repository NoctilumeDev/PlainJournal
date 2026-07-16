package com.ecommerce.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Instant;

@FunctionalInterface
public interface GatewayRateLimiter {

    Mono<Boolean> isAllowed(
            String policyName,
            String clientIdentifier,
            GatewayRateLimitProperties.Policy policy,
            Instant now
    );
}
