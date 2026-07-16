package com.ecommerce.gateway.filter;

import com.ecommerce.gateway.ratelimit.GatewayRateLimitProperties;
import com.ecommerce.gateway.ratelimit.GatewayRateLimiter;
import com.ecommerce.platform.common.api.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

@Component
public class GatewayRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final String LOGIN_PATH = "/api/v1/identity/auth/login";
    private static final String REGISTRATION_PATH = "/api/v1/identity/auth/register";
    private static final String REFRESH_PATH = "/api/v1/identity/auth/refresh";

    private final GatewayRateLimiter rateLimiter;
    private final GatewayRateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GatewayRateLimitGlobalFilter(
            GatewayRateLimiter rateLimiter,
            GatewayRateLimitProperties properties,
            ObjectMapper objectMapper) {
        this(rateLimiter, properties, objectMapper, Clock.systemUTC());
    }

    GatewayRateLimitGlobalFilter(
            GatewayRateLimiter rateLimiter,
            GatewayRateLimitProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Optional<NamedPolicy> selected = selectPolicy(exchange);
        if (!properties.enabled() || selected.isEmpty()) {
            return chain.filter(exchange);
        }

        NamedPolicy policy = selected.get();
        String clientIdentifier = clientIdentifier(exchange);
        return rateLimiter.isAllowed(policy.name(), clientIdentifier, policy.policy(), clock.instant())
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange, policy));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private Optional<NamedPolicy> selectPolicy(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return Optional.empty();
        }
        return switch (exchange.getRequest().getURI().getPath()) {
            case LOGIN_PATH -> Optional.of(new NamedPolicy("login", properties.login()));
            case REGISTRATION_PATH -> Optional.of(new NamedPolicy("registration", properties.registration()));
            case REFRESH_PATH -> Optional.of(new NamedPolicy("refresh", properties.refresh()));
            default -> Optional.empty();
        };
    }

    private String clientIdentifier(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange, NamedPolicy policy) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiResponse.failure(
                    "GATEWAY_RATE_LIMITED",
                    "Too many requests; try again later"
            ));
        } catch (JsonProcessingException exception) {
            body = "{\"code\":\"GATEWAY_RATE_LIMITED\",\"message\":\"Too many requests; try again later\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
                "Retry-After",
                Long.toString(Math.max(1, policy.policy().window().toSeconds()))
        );
        exchange.getResponse().getHeaders().set("X-RateLimit-Policy", policy.name());
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private record NamedPolicy(String name, GatewayRateLimitProperties.Policy policy) {
    }
}
