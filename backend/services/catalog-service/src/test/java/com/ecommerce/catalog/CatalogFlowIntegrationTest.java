package com.ecommerce.catalog;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.port.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class CatalogFlowIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    CatalogFlowIntegrationTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanCatalogData() {
        jdbcTemplate.update("DELETE FROM product_media");
        jdbcTemplate.update("DELETE FROM product_sku");
        jdbcTemplate.update("DELETE FROM product_spu");
        jdbcTemplate.update("DELETE FROM catalog_brand");
        jdbcTemplate.update("DELETE FROM catalog_category");
    }

    @Test
    void enforcesRolesAndCompletesProductPublishingAndMediaFlow() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Phones", "slug", "phones", "sortOrder", 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        long categoryId = dataId(adminPost("/api/v1/catalog/admin/categories", Map.of(
                "name", "Phones", "slug", "phones", "sortOrder", 10)));
        long brandId = dataId(adminPost("/api/v1/catalog/admin/brands", Map.of(
                "name", "Example Brand", "slug", "example-brand")));

        Map<String, Object> productRequest = Map.of(
                "categoryId", categoryId,
                "brandId", brandId,
                "title", "Example Phone",
                "subtitle", "A catalog integration product",
                "description", "Catalog data is owned by catalog-service.",
                "skus", List.of(Map.of(
                        "skuCode", "PHONE-BLACK-128",
                        "name", "Black / 128 GB",
                        "specJson", "{\"color\":\"black\",\"storage\":\"128GB\"}",
                        "salePrice", "129.90",
                        "marketPrice", "159.90"
                ))
        );
        JsonNode created = responseJson(adminPost("/api/v1/catalog/admin/products", productRequest));
        long productId = created.at("/data/id").asLong();
        assertThat(created.at("/data/id").isTextual()).isTrue();
        assertThat(created.at("/data/skus/0/id").isTextual()).isTrue();
        assertThat(created.at("/data/status").asText()).isEqualTo("DRAFT");
        assertThat(created.at("/data/skus/0/salePrice").decimalValue()).isEqualByComparingTo("129.90");

        mockMvc.perform(get("/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        JsonNode published = responseJson(adminPost(
                "/api/v1/catalog/admin/products/" + productId + "/publish",
                Map.of("expectedVersion", 0)));
        assertThat(published.at("/data/status").asText()).isEqualTo("ACTIVE");
        assertThat(published.at("/data/version").asInt()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/catalog/admin/products/{id}/publish", productId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").isString())
                .andExpect(jsonPath("$.data.items[0].category.id").isString())
                .andExpect(jsonPath("$.data.items[0].brand.id").isString())
                .andExpect(jsonPath("$.data.items[0].minimumPrice").value(129.90));

        when(objectStorage.createUploadUrl(eq("product-media"), any(), any()))
                .thenReturn("http://storage.invalid/upload");
        JsonNode intent = responseJson(adminPost(
                "/api/v1/catalog/admin/products/" + productId + "/media/upload-intents",
                Map.of("contentType", "image/png", "sizeBytes", 68)));
        String objectKey = intent.at("/data/objectKey").asText();
        assertThat(objectKey).startsWith("products/" + productId + "/").endsWith(".png");

        AtomicBoolean statInsideTransaction = new AtomicBoolean(true);
        when(objectStorage.stat("product-media", objectKey))
                .thenAnswer(ignored -> {
                    statInsideTransaction.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return new ObjectStorage.StoredObject(68, "image/png");
                });
        when(objectStorage.createDownloadUrl(eq("product-media"), eq(objectKey), any()))
                .thenAnswer(ignored -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return "http://storage.invalid/download";
                });
        adminPost("/api/v1/catalog/admin/products/" + productId + "/media", Map.of(
                "objectKey", objectKey,
                "sortOrder", 0
        ));
        assertThat(statInsideTransaction).isFalse();

        mockMvc.perform(get("/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.skus[0].id").isString())
                .andExpect(jsonPath("$.data.media[0].id").isString())
                .andExpect(jsonPath("$.data.media[0].mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.media[0].url").value("http://storage.invalid/download"));
        mockMvc.perform(get("/api/v1/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].coverUrl")
                        .value("http://storage.invalid/download"));
        mockMvc.perform(get("/api/v1/catalog/products/cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].coverUrl")
                        .value("http://storage.invalid/download"));

        when(objectStorage.createDownloadUrl(eq("product-media"), eq(objectKey), any()))
                .thenThrow(new CatalogException(CatalogError.MEDIA_STORAGE_UNAVAILABLE));
        mockMvc.perform(get("/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Example Phone"))
                .andExpect(jsonPath("$.data.media[0].url").doesNotExist());
    }

    @Test
    void rejectsInvalidPriceRelationshipsAndMedia() throws Exception {
        long categoryId = dataId(adminPost("/api/v1/catalog/admin/categories", Map.of(
                "name", "Computers", "slug", "computers", "sortOrder", 20)));
        long brandId = dataId(adminPost("/api/v1/catalog/admin/brands", Map.of(
                "name", "Safe Brand", "slug", "safe-brand")));

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "categoryId", categoryId,
                                "brandId", brandId,
                                "title", "Invalid price",
                                "skus", List.of(Map.of(
                                        "skuCode", "INVALID-PRICE",
                                        "name", "Invalid",
                                        "specJson", "{}",
                                        "salePrice", "100.00",
                                        "marketPrice", "90.00"
                                ))
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "categoryId", categoryId,
                                "brandId", brandId,
                                "title", "Excessive precision",
                                "skus", List.of(Map.of(
                                        "skuCode", "INVALID-PRECISION",
                                        "name", "Invalid",
                                        "specJson", "{}",
                                        "salePrice", "19.999"
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cursorPaginationIsStableAndRejectsMalformedCursors() throws Exception {
        long categoryId = dataId(adminPost("/api/v1/catalog/admin/categories", Map.of(
                "name", "Cursor Category", "slug", "cursor-category", "sortOrder", 30)));
        long brandId = dataId(adminPost("/api/v1/catalog/admin/brands", Map.of(
                "name", "Cursor Brand", "slug", "cursor-brand")));
        for (int index = 1; index <= 3; index++) {
            createPublishedProduct(categoryId, brandId, index);
        }

        JsonNode first = responseJson(mockMvc.perform(get("/api/v1/catalog/products/cursor")
                        .param("size", "2")
                        .param("categoryId", Long.toString(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isString())
                .andReturn().getResponse().getContentAsString());
        String cursor = first.at("/data/nextCursor").asText();

        JsonNode second = responseJson(mockMvc.perform(get("/api/v1/catalog/products/cursor")
                        .param("size", "2")
                        .param("categoryId", Long.toString(categoryId))
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty())
                .andReturn().getResponse().getContentAsString());

        List<String> productIds = new java.util.ArrayList<>();
        first.at("/data/items").forEach(item -> productIds.add(item.get("id").asText()));
        second.at("/data/items").forEach(item -> productIds.add(item.get("id").asText()));
        assertThat(productIds).hasSize(3).doesNotHaveDuplicates();

        mockMvc.perform(get("/api/v1/catalog/products/cursor")
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    private void createPublishedProduct(long categoryId, long brandId, int index) throws Exception {
        JsonNode created = responseJson(adminPost("/api/v1/catalog/admin/products", Map.of(
                "categoryId", categoryId,
                "brandId", brandId,
                "title", "Cursor Product " + index,
                "skus", List.of(Map.of(
                        "skuCode", "CURSOR-SKU-" + index,
                        "name", "Cursor SKU " + index,
                        "specJson", "{}",
                        "salePrice", "29.90"
                ))
        )));
        adminPost(
                "/api/v1/catalog/admin/products/" + created.at("/data/id").asText() + "/publish",
                Map.of("expectedVersion", 0));
    }

    private String adminPost(String uri, Object body) throws Exception {
        return mockMvc.perform(post(uri)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long dataId(String response) throws Exception {
        return responseJson(response).at("/data/id").asLong();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode responseJson(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
