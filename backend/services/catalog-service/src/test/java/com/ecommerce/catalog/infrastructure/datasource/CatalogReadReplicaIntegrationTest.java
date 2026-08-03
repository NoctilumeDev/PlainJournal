package com.ecommerce.catalog.infrastructure.datasource;

import com.ecommerce.catalog.application.service.CatalogService;
import com.ecommerce.catalog.interfaces.rest.PublicCatalogController;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-primary-routing;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecommerce.catalog.read-replica.enabled=true",
        "ecommerce.catalog.read-replica.fallback-to-primary=true",
        "ecommerce.catalog.read-replica.datasource.url="
                + "jdbc:h2:mem:catalog-replica-routing;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecommerce.catalog.read-replica.datasource.username=sa",
        "ecommerce.catalog.read-replica.datasource.password=",
        "ecommerce.catalog.read-replica.datasource.driver-class-name=org.h2.Driver",
        "ecommerce.catalog.read-replica.datasource.hikari.maximum-pool-size=2",
        "ecommerce.catalog.read-replica.datasource.hikari.minimum-idle=0",
        "ecommerce.catalog.cache.enabled=false"
})
@TestMethodOrder(OrderAnnotation.class)
class CatalogReadReplicaIntegrationTest {

    private static final long PRIMARY_CATEGORY_ID = 7600000000000000001L;
    private static final long REPLICA_CATEGORY_ID = 7600000000000000002L;

    private final MockMvc mockMvc;
    private final CatalogService catalogService;
    private final HikariDataSource replicaDataSource;
    private final JdbcTemplate primaryJdbc;
    private final JdbcTemplate replicaJdbc;
    private final MeterRegistry meterRegistry;

    @Autowired
    CatalogReadReplicaIntegrationTest(
            MockMvc mockMvc,
            CatalogService catalogService,
            @Qualifier("catalogPrimaryDataSource") DataSource primaryDataSource,
            @Qualifier("catalogReplicaDataSource") HikariDataSource replicaDataSource,
            MeterRegistry meterRegistry) {
        this.mockMvc = mockMvc;
        this.catalogService = catalogService;
        this.replicaDataSource = replicaDataSource;
        this.primaryJdbc = new JdbcTemplate(primaryDataSource);
        this.replicaJdbc = new JdbcTemplate(replicaDataSource);
        this.meterRegistry = meterRegistry;
    }

    @BeforeEach
    void prepareIndependentPrimaryAndReplicaRows() {
        cleanPrimaryRows();
        createReplicaCategoryTable();
        replicaJdbc.update("DELETE FROM catalog_category");
        insertCategory(primaryJdbc, PRIMARY_CATEGORY_ID, null, "Primary category", "m7-primary");
        insertCategory(replicaJdbc, REPLICA_CATEGORY_ID, null, "Replica category", "m7-replica");
    }

    @AfterEach
    void cleanRows() {
        cleanPrimaryRows();
        if (!replicaDataSource.isClosed()) {
            replicaJdbc.update("DELETE FROM catalog_category");
        }
    }

    @Test
    @Order(1)
    void everyPublicGetEndpointDeclaresReplicaEligibilityExplicitly() {
        Method[] getMethods = Arrays.stream(PublicCatalogController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .toArray(Method[]::new);

        assertThat(getMethods).isNotEmpty();
        assertThat(getMethods)
                .allMatch(method -> method.isAnnotationPresent(
                        com.ecommerce.catalog.application.routing.CatalogReplicaRead.class));
    }

    @Test
    @Order(2)
    void publicReadsUseReplicaWhileExplicitConsistencyHintAndDirectServiceUsePrimary()
            throws Exception {
        double replicaAttemptsBefore = counter(
                "ecommerce.catalog.datasource.connection.attempts", "target", "replica");
        double primaryHintsBefore = counter(
                "ecommerce.catalog.datasource.primary.hints", null, null);

        mockMvc.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Replica category"));

        mockMvc.perform(get("/api/v1/catalog/categories")
                        .header(CatalogReadConsistencyFilter.HEADER_NAME, "primary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Primary category"));

        assertThat(catalogService.listCategories())
                .extracting(category -> category.name())
                .containsExactly("Primary category");
        assertThat(counter(
                "ecommerce.catalog.datasource.connection.attempts", "target", "replica"))
                .isGreaterThan(replicaAttemptsBefore);
        assertThat(counter("ecommerce.catalog.datasource.primary.hints", null, null))
                .isEqualTo(primaryHintsBefore + 1);
        assertThat(replicaFlywayHistoryTableCount()).isZero();
    }

    @Test
    @Order(3)
    void writeTransactionAndItsParentLookupRemainOnPrimary() {
        catalogService.createCategory(
                PRIMARY_CATEGORY_ID,
                "Primary child",
                "m7-primary-child",
                1);

        assertThat(primaryJdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_category WHERE slug = 'm7-primary-child'",
                Integer.class)).isEqualTo(1);
        assertThat(replicaJdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_category WHERE slug = 'm7-primary-child'",
                Integer.class)).isZero();
    }

    @Test
    @Order(4)
    void replicaConnectionFailureReplaysReadOnceOnPrimary() throws Exception {
        double fallbackBefore = counter(
                "ecommerce.catalog.datasource.replica.fallbacks", null, null);
        replicaDataSource.close();

        mockMvc.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Primary category"));

        assertThat(counter("ecommerce.catalog.datasource.replica.fallbacks", null, null))
                .isEqualTo(fallbackBefore + 1);
        assertThat(counter(
                "ecommerce.catalog.datasource.replica.connection.failures", null, null))
                .isGreaterThanOrEqualTo(1);
    }

    private void createReplicaCategoryTable() {
        replicaJdbc.execute("""
                CREATE TABLE IF NOT EXISTS catalog_category (
                    id BIGINT NOT NULL PRIMARY KEY,
                    parent_id BIGINT NULL,
                    name VARCHAR(80) NOT NULL,
                    slug VARCHAR(100) NOT NULL UNIQUE,
                    status VARCHAR(20) NOT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    version INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(3) NOT NULL,
                    updated_at TIMESTAMP(3) NOT NULL
                )
                """);
    }

    private void insertCategory(
            JdbcTemplate jdbcTemplate,
            long id,
            Long parentId,
            String name,
            String slug) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        jdbcTemplate.update("""
                        INSERT INTO catalog_category
                            (id, parent_id, name, slug, status, sort_order, version,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', 0, 0, ?, ?)
                        """,
                id, parentId, name, slug, now, now);
    }

    private int replicaFlywayHistoryTableCount() {
        return replicaJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE LOWER(table_name) = 'flyway_schema_history'
                """, Integer.class);
    }

    private void cleanPrimaryRows() {
        primaryJdbc.update(
                "DELETE FROM catalog_category WHERE parent_id = ?",
                PRIMARY_CATEGORY_ID);
        primaryJdbc.update(
                "DELETE FROM catalog_category WHERE id IN (?, ?)",
                PRIMARY_CATEGORY_ID,
                REPLICA_CATEGORY_ID);
    }

    private double counter(String name, String tagName, String tagValue) {
        io.micrometer.core.instrument.search.Search search = meterRegistry.find(name);
        if (tagName != null) {
            search = search.tag(tagName, tagValue);
        }
        io.micrometer.core.instrument.Counter counter = search.counter();
        return counter == null ? 0 : counter.count();
    }
}
