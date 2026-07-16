package com.ecommerce.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdGlobalFilterTest {

    private final RequestIdGlobalFilter filter = new RequestIdGlobalFilter();

    @Test
    void keepsValidRequestIdAndAddsItToResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "request_12345678")
        );
        AtomicReference<String> forwardedRequestId = new AtomicReference<>();
        GatewayFilterChain chain = forwarded -> {
            forwardedRequestId.set(forwarded.getRequest().getHeaders()
                    .getFirst(RequestIdGlobalFilter.REQUEST_ID_HEADER));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(forwardedRequestId.get()).isEqualTo("request_12345678");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(RequestIdGlobalFilter.REQUEST_ID_HEADER)).isEqualTo("request_12345678");
    }

    @Test
    void replacesMalformedRequestId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/")
                        .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "bad value")
        );
        AtomicReference<String> forwardedRequestId = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, forwarded -> {
            forwardedRequestId.set(forwarded.getRequest().getHeaders()
                    .getFirst(RequestIdGlobalFilter.REQUEST_ID_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertThat(forwardedRequestId.get()).matches("[a-f0-9]{32}");
    }
}
