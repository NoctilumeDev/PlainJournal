package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.InventoryPort.ReservationCommand;
import com.ecommerce.trade.application.port.InventoryPort.ReservationLine;
import com.ecommerce.trade.application.port.InventoryPort.ReservationSnapshot;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpInventoryClientResponseLossTest {

    private static final String RESERVATION_NO = "RSV-RESPONSE-LOSS";
    private static final String ORDER_NO = "ORD-RESPONSE-LOSS";
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-20T13:00:00Z");

    private HttpServer server;
    private AtomicBoolean committed;
    private AtomicBoolean confirmationCommitted;

    @BeforeEach
    void startServer() throws IOException {
        committed = new AtomicBoolean();
        confirmationCommitted = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/inventory/internal/reservations", this::handleReservation);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void queriesTheCommittedReservationAfterThePostConnectionClosesWithoutAResponse() {
        HttpInventoryClient client = client();
        ReservationCommand command = new ReservationCommand(
                RESERVATION_NO,
                ORDER_NO,
                10L,
                EXPIRES_AT,
                List.of(new ReservationLine(101L, 2)));

        assertThatThrownBy(() -> client.reserve(command))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
        assertThat(committed).isTrue();

        ReservationSnapshot recovered = client.getReservation(RESERVATION_NO);

        assertThat(recovered.reservationNo()).isEqualTo(RESERVATION_NO);
        assertThat(recovered.orderNo()).isEqualTo(ORDER_NO);
        assertThat(recovered.status()).isEqualTo("RESERVED");
        assertThat(recovered.warehouseId()).isEqualTo(10L);
        assertThat(recovered.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(recovered.items()).containsExactly(new ReservationLine(101L, 2));
    }

    @Test
    void queriesTheConfirmedReservationAfterTheConfirmationResponseIsLost() {
        HttpInventoryClient client = client();

        assertThatThrownBy(() -> client.confirm(RESERVATION_NO))
                .isInstanceOfSatisfying(TradeException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE));
        assertThat(confirmationCommitted).isTrue();

        ReservationSnapshot recovered = client.getReservation(RESERVATION_NO);

        assertThat(recovered.status()).isEqualTo("CONFIRMED");
        assertThat(recovered.reservationNo()).isEqualTo(RESERVATION_NO);
        assertThat(recovered.orderNo()).isEqualTo(ORDER_NO);
    }

    private HttpInventoryClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RemoteClientProperties clientProperties = new RemoteClientProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "http://catalog-service",
                "http://identity-service",
                baseUrl,
                "http://marketing-service");
        SynchronousBoundaryResilienceProperties resilienceProperties =
                new SynchronousBoundaryResilienceProperties(
                        Duration.ofSeconds(5),
                        2,
                        Duration.ofMillis(10),
                        4,
                        2,
                        Duration.ZERO,
                        4,
                        2,
                        50,
                        Duration.ofSeconds(1),
                        1);
        TradeSynchronousBoundaryResilience resilience =
                new TradeSynchronousBoundaryResilience(
                        CircuitBreakerRegistry.ofDefaults(),
                        RetryRegistry.ofDefaults(),
                        BulkheadRegistry.ofDefaults(),
                        new SimpleMeterRegistry(),
                        clientProperties,
                        resilienceProperties);
        return new HttpInventoryClient(
                RestClient.builder().baseUrl(baseUrl).build(),
                new InternalClientProperties(
                        "test-internal-service-token-1234567890",
                        "trade-service"),
                resilience);
    }

    private void handleReservation(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().endsWith("/confirm")) {
                confirmationCommitted.set(true);
            } else {
                committed.set(true);
            }
            exchange.close();
            return;
        }
        byte[] response = reservationResponse().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String reservationResponse() {
        return """
                {
                  "code": "OK",
                  "message": "success",
                  "data": {
                    "reservationNo": "%s",
                    "orderNo": "%s",
                    "warehouseId": 10,
                    "status": "%s",
                    "expiresAt": "%s",
                    "version": 1,
                    "items": [
                      {
                        "skuId": 101,
                        "quantity": 2
                      }
                    ]
                  },
                  "timestamp": "2026-07-20T12:00:00Z"
                }
                """.formatted(
                RESERVATION_NO,
                ORDER_NO,
                confirmationCommitted.get() ? "CONFIRMED" : "RESERVED",
                EXPIRES_AT);
    }
}
