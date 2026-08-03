package com.ecommerce.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCredentialSanitizationGlobalFilterTest {

    private final InternalCredentialSanitizationGlobalFilter filter =
            new InternalCredentialSanitizationGlobalFilter();

    @Test
    void removesCallerSuppliedInternalCredentialsBeforeRouting() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/catalog/products")
                        .header("X-Internal-Service", "trade-service")
                        .header("X-Internal-Token", "attacker-controlled")
                        .header("X-Request-Id", "request-123")
                        .build());
        AtomicReference<ServerWebExchangeHeaders> forwarded = new AtomicReference<>();

        filter.filter(exchange, sanitized -> {
            forwarded.set(new ServerWebExchangeHeaders(
                    sanitized.getRequest().getHeaders().getFirst("X-Internal-Service"),
                    sanitized.getRequest().getHeaders().getFirst("X-Internal-Token"),
                    sanitized.getRequest().getHeaders().getFirst("X-Request-Id")));
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isEqualTo(
                new ServerWebExchangeHeaders(null, null, "request-123"));
    }

    private record ServerWebExchangeHeaders(
            String caller,
            String token,
            String requestId) {
    }
}
