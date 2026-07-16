package com.ecommerce.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiBlockGlobalFilterTest {

    private final InternalApiBlockGlobalFilter filter = new InternalApiBlockGlobalFilter();

    @Test
    void blocksInternalServicePathsAtThePublicGateway() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/inventory/internal/reservations").build());
        AtomicBoolean chained = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            chained.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chained).isFalse();
    }

    @Test
    void allowsPublicAndAdministrativePathsToContinue() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/inventory/stocks/1").build());
        AtomicBoolean chained = new AtomicBoolean();
        GatewayFilterChain chain = ignored -> {
            chained.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chained).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
