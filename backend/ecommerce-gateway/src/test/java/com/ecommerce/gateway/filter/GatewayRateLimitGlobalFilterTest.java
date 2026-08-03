package com.ecommerce.gateway.filter;

import com.ecommerce.gateway.ratelimit.GatewayRateLimitProperties;
import com.ecommerce.gateway.ratelimit.GatewayRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRateLimitGlobalFilterTest {

    @Test
    void rejectsLimitedAuthenticationRequestWithStableJsonResponse() {
        GatewayRateLimiter limiter = (name, client, policy, now) -> Mono.just(false);
        GatewayRateLimitGlobalFilter filter = new GatewayRateLimitGlobalFilter(
                limiter,
                properties(true),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/identity/auth/login")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
        );
        AtomicBoolean forwarded = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("login");
        String body = DataBufferUtils.join(exchange.getResponse().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .block();
        assertThat(body).contains("GATEWAY_RATE_LIMITED");
    }

    @Test
    void bypassesNonAuthenticationRoutes() {
        GatewayRateLimiter limiter = (name, client, policy, now) -> Mono.error(
                new AssertionError("rate limiter should not be called")
        );
        GatewayRateLimitGlobalFilter filter = new GatewayRateLimitGlobalFilter(
                limiter,
                properties(true),
                new ObjectMapper(),
                Clock.systemUTC()
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/identity/status")
        );
        AtomicBoolean forwarded = new AtomicBoolean(false);
        GatewayFilterChain chain = ignored -> {
            forwarded.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        assertThat(forwarded).isTrue();
    }

    @Test
    void appliesIndependentPolicyToFlashSaleAdmissions() {
        GatewayRateLimiter limiter = (name, client, policy, now) -> {
            assertThat(name).isEqualTo("flash-sale");
            assertThat(policy.limit()).isEqualTo(60);
            assertThat(policy.window()).isEqualTo(Duration.ofSeconds(1));
            return Mono.just(false);
        };
        GatewayRateLimitGlobalFilter filter = new GatewayRateLimitGlobalFilter(
                limiter,
                properties(true),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC)
        );
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/marketing/flash-sales/FSA100/admissions")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
        );

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("flash-sale");
    }

    private GatewayRateLimitProperties properties(boolean enabled) {
        GatewayRateLimitProperties.Policy policy = new GatewayRateLimitProperties.Policy(10, Duration.ofMinutes(1));
        return new GatewayRateLimitProperties(
                "test",
                enabled,
                false,
                Duration.ofMillis(100),
                1000,
                policy,
                new GatewayRateLimitProperties.Policy(5, Duration.ofMinutes(1)),
                new GatewayRateLimitProperties.Policy(30, Duration.ofMinutes(1)),
                new GatewayRateLimitProperties.Policy(60, Duration.ofSeconds(1))
        );
    }
}
