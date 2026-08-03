package com.ecommerce.catalog.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchProductSearchIndexTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void scansVersionsWithSearchAfterInsteadOfExceedingTheResultWindow() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0);
        server.createContext("/products-test/_search", exchange ->
                handleVersionScan(exchange, requests));
        server.start();

        CatalogSearchProperties properties = new CatalogSearchProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "products-test",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                20,
                3,
                Duration.ofMillis(10),
                Duration.ofSeconds(5),
                "search-test",
                200,
                20_000,
                true,
                false,
                Duration.ZERO,
                Duration.ofMinutes(5));
        OpenSearchProductSearchIndex index =
                new OpenSearchProductSearchIndex(properties, objectMapper);

        var versions = index.scanVersions(1_001);

        assertThat(versions).hasSize(1_001);
        assertThat(versions.get(1L)).isEqualTo(11L);
        assertThat(versions.get(1_001L)).isEqualTo(10_011L);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("size").asInt()).isEqualTo(1_000);
        assertThat(requests.get(0).has("search_after")).isFalse();
        assertThat(requests.get(1).path("size").asInt()).isOne();
        assertThat(requests.get(1).at("/search_after/0").asLong()).isEqualTo(1_000L);
    }

    private void handleVersionScan(
            HttpExchange exchange,
            List<JsonNode> requests) throws IOException {
        try (exchange) {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            requests.add(request);
            long firstId = request.has("search_after")
                    ? request.at("/search_after/0").asLong() + 1
                    : 1;
            int size = request.path("size").asInt();
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode hits = response.putObject("hits").putArray("hits");
            for (long productId = firstId; productId < firstId + size; productId++) {
                ObjectNode hit = hits.addObject();
                hit.putObject("_source")
                        .put("productId", productId)
                        .put("revision", productId * 10 + 1);
                hit.putArray("sort").add(productId);
            }
            byte[] body = objectMapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json; charset=" + StandardCharsets.UTF_8.name());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
