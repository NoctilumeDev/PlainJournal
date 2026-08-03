package com.ecommerce.catalog.infrastructure.search;

import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpenSearchProductSearchIndex implements ProductSearchIndex {

    private static final Set<Integer> OK = Set.of(200, 201);
    private static final Set<Integer> OK_OR_NOT_FOUND = Set.of(200, 201, 404);
    private static final Set<Integer> OK_OR_SUPERSEDED = Set.of(200, 201, 409);
    private static final Set<Integer> OK_OR_NOT_FOUND_OR_SUPERSEDED = Set.of(200, 201, 404, 409);
    private static final int VERSION_SCAN_PAGE_SIZE = 1_000;

    private final CatalogSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object aliasInitializationMonitor = new Object();

    public OpenSearchProductSearchIndex(
            CatalogSearchProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public SearchResult search(String query, Long categoryId, int from, int size) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("from", from);
        root.put("size", size);
        root.put("track_total_hits", true);
        root.put("_source", false);

        ObjectNode bool = root.putObject("query").putObject("bool");
        ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
        multiMatch.put("query", query);
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "and");
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("title^5");
        fields.add("subtitle^3");
        fields.add("categoryName^2");
        fields.add("brandName^2");
        fields.add("skuNames^2");
        fields.add("description");
        fields.add("skuSpecs");
        if (categoryId != null) {
            bool.putArray("filter").addObject().putObject("term").put("categoryId", categoryId);
        }
        root.putArray("sort")
                .addObject().putObject("_score").put("order", "desc");
        root.withArray("sort")
                .addObject().putObject("updatedAt").put("order", "desc");
        root.withArray("sort")
                .addObject().putObject("productId").put("order", "desc");

        JsonNode response = requestJson(
                "POST",
                "/" + properties.indexAlias() + "/_search",
                root,
                OK);
        List<Long> ids = new ArrayList<>();
        response.at("/hits/hits").forEach(hit -> ids.add(Long.valueOf(hit.path("_id").asText())));
        return new SearchResult(ids, response.at("/hits/total/value").asLong());
    }

    @Override
    public void upsert(SearchProductDocument document) {
        ensureWritableAlias();
        requestJson(
                "PUT",
                "/" + properties.indexAlias() + "/_doc/" + document.productId()
                        + "?version=" + document.revision() + "&version_type=external_gte",
                objectMapper.valueToTree(document),
                OK_OR_SUPERSEDED);
    }

    @Override
    public void delete(Long productId, long revision) {
        ensureWritableAlias();
        requestJson(
                "DELETE",
                "/" + properties.indexAlias() + "/_doc/" + productId
                        + "?version=" + revision + "&version_type=external_gte",
                null,
                OK_OR_NOT_FOUND_OR_SUPERSEDED);
    }

    @Override
    public void createIndex(String indexName) {
        requestJson("PUT", "/" + indexName, indexDefinition(), OK);
    }

    @Override
    public void deleteIndex(String indexName) {
        requestJson("DELETE", "/" + indexName, null, OK_OR_NOT_FOUND);
    }

    @Override
    public void bulkIndex(String indexName, List<SearchProductDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder(documents.size() * 512);
        for (SearchProductDocument document : documents) {
            ObjectNode action = objectMapper.createObjectNode();
            action.putObject("index")
                    .put("_index", indexName)
                    .put("_id", document.productId());
            appendJsonLine(body, action);
            appendJsonLine(body, objectMapper.valueToTree(document));
        }
        JsonNode response = requestJson(
                "POST",
                "/_bulk?refresh=true",
                body.toString(),
                "application/x-ndjson",
                OK);
        if (response.path("errors").asBoolean()) {
            throw new SearchIndexUnavailableException(
                    "OpenSearch bulk indexing returned item failures");
        }
    }

    @Override
    public void replaceAlias(String targetIndex) {
        Set<String> current = aliasIndices();
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode actions = request.putArray("actions");
        for (String index : current) {
            if (!targetIndex.equals(index)) {
                actions.addObject().putObject("remove")
                        .put("index", index)
                        .put("alias", properties.indexAlias());
            }
        }
        if (!current.contains(targetIndex)) {
            actions.addObject().putObject("add")
                    .put("index", targetIndex)
                    .put("alias", properties.indexAlias());
        }
        requestJson("POST", "/_aliases", request, OK);
    }

    @Override
    public boolean aliasTargets(String indexName) {
        return aliasIndices().contains(indexName);
    }

    @Override
    public void deleteOwnedIndicesExcept(String retainedIndex) {
        JsonNode response = requestJson(
                "GET",
                "/_cat/indices/" + properties.indexAlias() + "-*?format=json&h=index",
                null,
                OK_OR_NOT_FOUND);
        if (!response.isArray()) {
            return;
        }
        for (JsonNode item : response) {
            String index = item.path("index").asText();
            if (!index.isBlank() && !retainedIndex.equals(index)
                    && index.startsWith(properties.indexAlias() + "-")) {
                requestJson("DELETE", "/" + index, null, OK_OR_NOT_FOUND);
            }
        }
    }

    @Override
    public Map<Long, Long> scanVersions(int limit) {
        if (limit <= 0) {
            return Map.of();
        }
        Map<Long, Long> versions = new LinkedHashMap<>();
        Long afterProductId = null;
        while (versions.size() < limit) {
            int pageSize = Math.min(VERSION_SCAN_PAGE_SIZE, limit - versions.size());
            ObjectNode request = objectMapper.createObjectNode();
            request.put("size", pageSize);
            request.put("track_total_hits", false);
            request.putObject("query").putObject("match_all");
            ArrayNode source = request.putArray("_source");
            source.add("productId");
            source.add("revision");
            request.putArray("sort").addObject().putObject("productId").put("order", "asc");
            if (afterProductId != null) {
                request.putArray("search_after").add(afterProductId);
            }
            JsonNode response = requestJson(
                    "POST",
                    "/" + properties.indexAlias() + "/_search",
                    request,
                    OK);
            JsonNode hits = response.at("/hits/hits");
            if (!hits.isArray() || hits.isEmpty()) {
                break;
            }
            Long previousAfter = afterProductId;
            for (JsonNode hit : hits) {
                JsonNode productIdNode = hit.at("/_source/productId");
                JsonNode revisionNode = hit.at("/_source/revision");
                if (!productIdNode.canConvertToLong() || !revisionNode.canConvertToLong()) {
                    throw new SearchIndexUnavailableException(
                            "OpenSearch version scan returned an incomplete document");
                }
                versions.put(productIdNode.longValue(), revisionNode.longValue());
            }
            JsonNode lastSort = hits.get(hits.size() - 1).at("/sort/0");
            if (!lastSort.canConvertToLong()) {
                throw new SearchIndexUnavailableException(
                        "OpenSearch version scan omitted the search_after sort value");
            }
            afterProductId = lastSort.longValue();
            if (previousAfter != null && afterProductId <= previousAfter) {
                throw new SearchIndexUnavailableException(
                        "OpenSearch version scan did not advance");
            }
            if (hits.size() < pageSize) {
                break;
            }
        }
        return Map.copyOf(versions);
    }

    private void ensureWritableAlias() {
        if (exists("/_alias/" + properties.indexAlias())) {
            return;
        }
        synchronized (aliasInitializationMonitor) {
            if (exists("/_alias/" + properties.indexAlias())) {
                return;
            }
            String bootstrapIndex = properties.indexAlias() + "-bootstrap";
            if (!exists("/" + bootstrapIndex)) {
                createIndex(bootstrapIndex);
            }
            ObjectNode request = objectMapper.createObjectNode();
            request.putArray("actions").addObject().putObject("add")
                    .put("index", bootstrapIndex)
                    .put("alias", properties.indexAlias());
            requestJson("POST", "/_aliases", request, OK);
        }
    }

    private Set<String> aliasIndices() {
        if (!exists("/_alias/" + properties.indexAlias())) {
            return Set.of();
        }
        JsonNode response = requestJson(
                "GET",
                "/_alias/" + properties.indexAlias(),
                null,
                OK);
        if (!response.isObject() || response.isEmpty()) {
            return Set.of();
        }
        Set<String> indices = new HashSet<>();
        response.fieldNames().forEachRemaining(indices::add);
        return Set.copyOf(indices);
    }

    private boolean exists(String path) {
        HttpResponse<String> response = send("HEAD", path, null, null);
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw failure(path, response);
    }

    private ObjectNode indexDefinition() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("settings")
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .put("refresh_interval", "1s");
        ObjectNode propertiesNode = root.putObject("mappings").putObject("properties");
        propertiesNode.putObject("productId").put("type", "long");
        propertiesNode.putObject("revision").put("type", "long");
        propertiesNode.putObject("categoryId").put("type", "long");
        propertiesNode.putObject("categoryName").put("type", "text");
        propertiesNode.putObject("brandId").put("type", "long");
        propertiesNode.putObject("brandName").put("type", "text");
        propertiesNode.putObject("title").put("type", "text");
        propertiesNode.putObject("subtitle").put("type", "text");
        propertiesNode.putObject("description").put("type", "text");
        propertiesNode.putObject("skuNames").put("type", "text");
        propertiesNode.putObject("skuSpecs").put("type", "text");
        propertiesNode.putObject("updatedAt").put("type", "date");
        return root;
    }

    private void appendJsonLine(StringBuilder target, JsonNode node) {
        try {
            target.append(objectMapper.writeValueAsString(node)).append('\n');
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Search document serialization failed", exception);
        }
    }

    private JsonNode requestJson(String method, String path, JsonNode body, Set<Integer> accepted) {
        return requestJson(
                method,
                path,
                body == null ? null : serialize(body),
                "application/json",
                accepted);
    }

    private JsonNode requestJson(
            String method,
            String path,
            String body,
            String contentType,
            Set<Integer> accepted) {
        HttpResponse<String> response = send(method, path, body, contentType);
        if (!accepted.contains(response.statusCode())) {
            throw failure(path, response);
        }
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new SearchIndexUnavailableException(
                    "OpenSearch returned an invalid JSON response for " + path, exception);
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                    .timeout(properties.requestTimeout())
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", contentType)
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SearchIndexUnavailableException("OpenSearch request failed for " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchIndexUnavailableException("OpenSearch request was interrupted for " + path, exception);
        }
    }

    private URI resolve(String path) {
        String base = properties.endpoint().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private String serialize(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OpenSearch request serialization failed", exception);
        }
    }

    private SearchIndexUnavailableException failure(String path, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 500) {
            body = body.substring(0, 500);
        }
        return new SearchIndexUnavailableException(
                "OpenSearch request failed: path=" + path + ", status=" + response.statusCode()
                        + ", response=" + body);
    }
}
