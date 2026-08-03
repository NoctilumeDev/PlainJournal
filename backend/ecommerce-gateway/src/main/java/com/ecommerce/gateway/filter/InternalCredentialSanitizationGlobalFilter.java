package com.ecommerce.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class InternalCredentialSanitizationGlobalFilter implements GlobalFilter, Ordered {

    static final String CALLER_HEADER = "X-Internal-Service";
    static final String TOKEN_HEADER = "X-Internal-Token";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange sanitized = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> {
                            headers.remove(CALLER_HEADER);
                            headers.remove(TOKEN_HEADER);
                        })
                        .build())
                .build();
        return chain.filter(sanitized);
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 10;
    }
}
