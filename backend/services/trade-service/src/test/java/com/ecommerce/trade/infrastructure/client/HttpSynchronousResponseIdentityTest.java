package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.config.SynchronousBoundaryResilienceProperties;
import com.ecommerce.trade.infrastructure.resilience.TradeSynchronousBoundaryResilience;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSynchronousResponseIdentityTest {

    private static final String INTERNAL_TOKEN =
            "test-internal-service-token-with-at-least-32-characters";

    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsAProductResponseForADifferentProduct() {
        responseBody.set(success("""
                {
                  "id": 2,
                  "title": "Wrong product",
                  "status": "ACTIVE",
                  "skus": [],
                  "media": []
                }
                """));

        assertRemoteUnavailable(() -> new HttpCatalogClient(restClient(), resilience())
                .getProduct(1L));
    }

    @Test
    void rejectsAnAddressResponseForADifferentAddress() {
        responseBody.set(success("""
                {
                  "id": 502,
                  "recipientName": "Wrong address",
                  "phone": "13800000000",
                  "province": "Zhejiang",
                  "provinceCode": "330000",
                  "city": "Hangzhou",
                  "cityCode": "330100",
                  "district": "Xihu",
                  "districtCode": "330106",
                  "detailAddress": "Wrong street",
                  "postalCode": "310000"
                }
                """));

        assertRemoteUnavailable(() -> new HttpIdentityClient(
                restClient(), internalProperties(), resilience()).getAddress(1L, 501L));
    }

    @Test
    void rejectsAWarehouseResponseForADifferentCode() {
        responseBody.set(success("""
                {
                  "id": 10,
                  "code": "SECONDARY",
                  "status": "ACTIVE"
                }
                """));

        assertRemoteUnavailable(() -> inventoryClient().getWarehouse("PRIMARY"));
    }

    @Test
    void rejectsAReservationQueryResponseForADifferentReservation() {
        responseBody.set(success("""
                {
                  "reservationNo": "RSV-OTHER",
                  "orderNo": "ORD-001",
                  "warehouseId": 10,
                  "status": "RELEASED",
                  "expiresAt": "2026-07-23T00:00:00Z",
                  "items": []
                }
                """));

        assertRemoteUnavailable(() -> inventoryClient().getReservation("RSV-EXPECTED"));
    }

    @Test
    void rejectsAReservationReleaseResponseForADifferentReservation() {
        responseBody.set(success("""
                {
                  "reservationNo": "RSV-OTHER",
                  "orderNo": "ORD-001",
                  "warehouseId": 10,
                  "status": "RELEASED",
                  "expiresAt": "2026-07-23T00:00:00Z",
                  "items": []
                }
                """));

        assertRemoteUnavailable(() -> inventoryClient().release("RSV-EXPECTED"));
    }

    private HttpInventoryClient inventoryClient() {
        return new HttpInventoryClient(restClient(), internalProperties(), resilience());
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
    }

    private InternalClientProperties internalProperties() {
        return new InternalClientProperties(INTERNAL_TOKEN, "trade-service");
    }

    private TradeSynchronousBoundaryResilience resilience() {
        RemoteClientProperties clients = new RemoteClientProperties(
                Duration.ofMillis(100),
                Duration.ofMillis(500),
                "http://catalog-service",
                "http://identity-service",
                "http://inventory-service",
                "http://marketing-service");
        SynchronousBoundaryResilienceProperties properties =
                new SynchronousBoundaryResilienceProperties(
                        Duration.ofSeconds(2),
                        1,
                        Duration.ZERO,
                        4,
                        2,
                        Duration.ZERO,
                        4,
                        2,
                        50,
                        Duration.ofSeconds(1),
                        1);
        return new TradeSynchronousBoundaryResilience(
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                clients,
                properties);
    }

    private void assertRemoteUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
    }

    private String success(String data) {
        return """
                {
                  "code": "OK",
                  "message": "success",
                  "data": %s,
                  "timestamp": "2026-07-23T00:00:00Z"
                }
                """.formatted(data);
    }

    private void respond(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] content = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, content.length);
        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }
}
