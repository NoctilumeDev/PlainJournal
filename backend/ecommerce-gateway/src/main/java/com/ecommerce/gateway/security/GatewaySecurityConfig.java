package com.ecommerce.gateway.security;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.gateway.filter.RequestIdGlobalFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecretKey gatewayJwtSecretKey(GatewayTokenProperties properties) {
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
    }

    @Bean
    public ReactiveJwtDecoder gatewayJwtDecoder(
            SecretKey gatewayJwtSecretKey,
            GatewayTokenProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(gatewayJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder gatewayJwtDecoder,
            ObjectMapper objectMapper) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .pathMatchers(
                                "/api/v1/identity/internal/**",
                                "/api/v1/inventory/internal/**",
                                "/api/v1/trade/internal/**",
                                "/api/v1/marketing/internal/**"
                        ).denyAll()
                        .pathMatchers(
                                "/api/v1/identity/status",
                                "/api/v1/catalog/status",
                                "/api/v1/inventory/status",
                                "/api/v1/trade/status",
                                "/api/v1/payment/status",
                                "/api/v1/fulfillment/status",
                                "/api/v1/marketing/status",
                                "/api/v1/chat/status",
                                "/api/v1/notifications/status",
                                "/api/v1/analytics/status"
                        ).permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/trade/status/distributed-id",
                                "/api/v1/catalog/categories",
                                "/api/v1/catalog/brands",
                                "/api/v1/catalog/products",
                                "/api/v1/catalog/products/**",
                                "/api/v1/catalog/search/products",
                                "/api/v1/inventory/stocks/**",
                                "/api/v1/marketing/flash-sales/*"
                        ).permitAll()
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/identity/auth/register",
                                "/api/v1/identity/auth/login",
                                "/api/v1/identity/auth/refresh",
                                "/api/v1/identity/auth/logout",
                                "/api/v1/payment/callbacks/mock",
                                "/api/v1/payment/callbacks/mock/refunds"
                        ).permitAll()
                        .pathMatchers("/ws/chat").permitAll()
                        .pathMatchers("/api/v1/catalog/admin/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers("/api/v1/inventory/admin/**")
                        .hasAnyRole("ADMIN", "WAREHOUSE")
                        .pathMatchers("/api/v1/trade/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/payment/admin/**").hasRole("ADMIN")
                        .pathMatchers(
                                "/api/v1/fulfillment/admin/orders/*/exception/resolve"
                        ).hasRole("ADMIN")
                        .pathMatchers("/api/v1/fulfillment/admin/**")
                        .hasAnyRole("ADMIN", "WAREHOUSE")
                        .pathMatchers("/api/v1/marketing/admin/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers(
                                "/api/v1/chat/admin/**",
                                "/api/v1/chat/conversations/*/claim"
                        ).hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers("/api/v1/notifications/admin/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers("/api/v1/analytics/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers(
                                "/api/v1/catalog/review-eligibilities",
                                "/api/v1/catalog/reviews",
                                "/api/v1/catalog/reviews/**"
                        ).hasRole("CUSTOMER")
                        .pathMatchers("/api/v1/trade/**", "/api/v1/payment/**")
                        .hasAnyRole("CUSTOMER", "ADMIN")
                        .pathMatchers("/api/v1/marketing/**")
                        .hasAnyRole("CUSTOMER", "ADMIN")
                        .pathMatchers("/api/v1/chat/**")
                        .hasAnyRole("CUSTOMER", "ADMIN", "OPERATOR")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt
                                .jwtDecoder(gatewayJwtDecoder)
                                .jwtAuthenticationConverter(
                                        new ReactiveJwtAuthenticationConverterAdapter(converter)))
                        .authenticationEntryPoint((exchange, exception) ->
                                writeFailure(
                                        exchange,
                                        objectMapper,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication is required")))
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((exchange, exception) -> {
                            if (isInternalPath(exchange)) {
                                return writeFailure(
                                        exchange,
                                        objectMapper,
                                        HttpStatus.NOT_FOUND,
                                        "NOT_FOUND",
                                        "Resource not found");
                            }
                            return writeFailure(
                                    exchange,
                                    objectMapper,
                                    HttpStatus.FORBIDDEN,
                                    "FORBIDDEN",
                                    "Access is denied");
                        }))
                .build();
    }

    private boolean isInternalPath(ServerWebExchange exchange) {
        return Arrays.stream(exchange.getRequest().getPath().pathWithinApplication()
                        .value().split("/"))
                .anyMatch("internal"::equals);
    }

    private Mono<Void> writeFailure(
            ServerWebExchange exchange,
            ObjectMapper objectMapper,
            HttpStatus status,
            String code,
            String message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiResponse.failure(code, message));
        } catch (JsonProcessingException exception) {
            body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
                RequestIdGlobalFilter.REQUEST_ID_HEADER,
                RequestIdGlobalFilter.resolveRequestId(
                        exchange.getRequest().getHeaders().getFirst(
                                RequestIdGlobalFilter.REQUEST_ID_HEADER)));
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }
}
