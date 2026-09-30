package com.ecommerce.catalog.infrastructure.search;

import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchProductDocument;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchProductSearchIndexTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
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

    @Test
    void acceptsVersionConflictOnlyWhenVisibleDocumentSupersedesTheAttempt() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        startVersionConflictServer(true, 12, requests);
        OpenSearchProductSearchIndex index = new OpenSearchProductSearchIndex(
                properties(), objectMapper);

        index.upsert(document(11));

        assertThat(requests).containsExactly(
                "HEAD /_alias/products-test",
                "PUT /products-test/_doc/1?version=11&version_type=external_gte",
                "GET /products-test/_doc/1");
    }

    @Test
    void rejectsVersionConflictWhenNoVisibleDocumentProvesSupersession() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        startVersionConflictServer(false, -1, requests);
        OpenSearchProductSearchIndex index = new OpenSearchProductSearchIndex(
                properties(), objectMapper);

        assertThatThrownBy(() -> index.upsert(document(11)))
                .isInstanceOf(SearchIndexUnavailableException.class)
                .hasMessageContaining("status=409");
        assertThat(requests).containsExactly(
                "HEAD /_alias/products-test",
                "PUT /products-test/_doc/1?version=11&version_type=external_gte",
                "GET /products-test/_doc/1");
    }

    private CatalogSearchProperties properties() {
        return new CatalogSearchProperties(
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
    }

    private SearchProductDocument document(long revision) {
        return new SearchProductDocument(
                1L,
                revision,
                2L,
                "Category",
                3L,
                "Brand",
                "Title",
                "Subtitle",
                "Description",
                List.of("SKU"),
                List.of("{}"),
                Instant.parse("2026-09-30T00:00:00Z"));
    }

    private void startVersionConflictServer(
            boolean found,
            long revision,
            List<String> requests) throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
                requests.add(request);
                if ("HEAD /_alias/products-test".equals(request)) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                if (request.startsWith("PUT /products-test/_doc/1")) {
                    sendJson(exchange, 409, "{\"error\":\"version conflict\"}");
                    return;
                }
                if ("GET /products-test/_doc/1".equals(request) && found) {
                    sendJson(exchange, 200, "{\"found\":true,\"_source\":{\"revision\":"
                            + revision + "}}");
                    return;
                }
                if ("GET /products-test/_doc/1".equals(request)) {
                    sendJson(exchange, 404, "{\"found\":false}");
                    return;
                }
                sendJson(exchange, 500, "{\"error\":\"unexpected request\"}");
            }
        });
        server.start();
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
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
