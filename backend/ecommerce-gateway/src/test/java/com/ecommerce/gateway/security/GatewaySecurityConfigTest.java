package com.ecommerce.gateway.security;

import com.ecommerce.gateway.filter.RequestIdGlobalFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Instant;
import java.util.Date;
import java.util.List;

@SpringJUnitConfig(GatewaySecurityConfigTest.TestConfig.class)
class GatewaySecurityConfigTest {

    private static final String SECRET =
            "test-only-gateway-jwt-secret-with-at-least-32-characters";
    private static final String ISSUER = "ecommerce-identity-test";

    private final WebTestClient client;

    @Autowired
    GatewaySecurityConfigTest(ApplicationContext context) {
        this.client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void protectedRouteRequiresAuthentication() {
        client.get()
                .uri("/api/v1/trade/orders")
                .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "gateway_auth_0001")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(
                        RequestIdGlobalFilter.REQUEST_ID_HEADER,
                        "gateway_auth_0001")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void publicCatalogRouteRemainsAnonymous() {
        client.get()
                .uri("/api/v1/catalog/products")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void healthProbesRemainAnonymousWithoutOpeningOtherActuatorEndpoints() {
        client.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        client.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validCustomerTokenPassesCoarseCustomerBoundary() throws Exception {
        client.get()
                .uri("/api/v1/trade/orders")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("CUSTOMER"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void customerCannotPassAdministrativeBoundary() throws Exception {
        client.post()
                .uri("/api/v1/payment/admin/refunds/RF1/retry-dispatch")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("CUSTOMER"))
                .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "gateway_forbidden_0001")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(
                        RequestIdGlobalFilter.REQUEST_ID_HEADER,
                        "gateway_forbidden_0001")
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void internalRouteIsHiddenEvenFromAuthenticatedClients() throws Exception {
        client.post()
                .uri("/api/v1/inventory/internal/reservations")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("ADMIN"))
                .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "gateway_internal_0001")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(
                        RequestIdGlobalFilter.REQUEST_ID_HEADER,
                        "gateway_internal_0001")
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void onlyAdminCanPassFulfillmentExceptionResolutionBoundary() throws Exception {
        String path =
                "/api/v1/fulfillment/admin/orders/FUL1/exception/resolve";
        client.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearerToken("WAREHOUSE"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN");

        client.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearerToken("ADMIN"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void malformedRequestIdIsNotReflectedBySecurityRejection() {
        client.get()
                .uri("/api/v1/trade/orders")
                .header(RequestIdGlobalFilter.REQUEST_ID_HEADER, "bad value")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(
                        RequestIdGlobalFilter.REQUEST_ID_HEADER,
                        "[a-f0-9]{32}");
    }

    @Test
    void operatorCanPassCatalogAdministrationBoundary() throws Exception {
        client.post()
                .uri("/api/v1/catalog/admin/products")
                .header(HttpHeaders.AUTHORIZATION, bearerToken("OPERATOR"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void expiredTokenIsRejectedAtGateway() throws Exception {
        client.get()
                .uri("/api/v1/trade/orders")
                .header(HttpHeaders.AUTHORIZATION, bearerToken(
                        Instant.now().minusSeconds(120),
                        Instant.now().minusSeconds(60),
                        "CUSTOMER"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String bearerToken(String... roles) throws Exception {
        return bearerToken(
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300),
                roles);
    }

    private String bearerToken(
            Instant issuedAt,
            Instant expiresAt,
            String... roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("1001")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("roles", List.of(roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                claims);
        JWSSigner signer = new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jwt.sign(signer);
        return "Bearer " + jwt.serialize();
    }

    @Configuration
    @EnableWebFlux
    @Import(GatewaySecurityConfig.class)
    static class TestConfig {

        @Bean
        GatewayTokenProperties gatewayTokenProperties() {
            return new GatewayTokenProperties(SECRET, ISSUER);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        RouterFunction<ServerResponse> catchAllRoute() {
            return RouterFunctions.route()
                    .route(request -> true, request -> ServerResponse.ok().build())
                    .build();
        }
    }
}
