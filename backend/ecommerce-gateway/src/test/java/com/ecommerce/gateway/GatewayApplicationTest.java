package com.ecommerce.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class GatewayApplicationTest {

    private final ApplicationContext applicationContext;

    @Autowired
    GatewayApplicationTest(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void contextLoads() {
    }

    @Test
    void securityChainDeniesInternalPathsWithoutDependingOnGatewayGlobalFilters() {
        WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .build()
                .mutateWith(mockJwt())
                .post()
                .uri("/api/v1/inventory/internal/reservations")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }
}
