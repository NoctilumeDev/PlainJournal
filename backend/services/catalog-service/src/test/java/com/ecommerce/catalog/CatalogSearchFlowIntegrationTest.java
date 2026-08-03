package com.ecommerce.catalog;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.SearchModels.SearchRebuildView;
import com.ecommerce.catalog.application.model.SearchModels.SearchReconciliationResult;
import com.ecommerce.catalog.application.port.ObjectStorage;
import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchResult;
import com.ecommerce.catalog.application.service.CatalogSearchOutboxService;
import com.ecommerce.catalog.application.service.CatalogSearchRebuildCutoverService;
import com.ecommerce.catalog.application.service.CatalogSearchRebuildService;
import com.ecommerce.catalog.application.service.CatalogSearchReconciliationService;
import com.ecommerce.catalog.application.service.CatalogService;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchOutboxEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchReconciliationEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchOutboxMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchReconciliationMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchRebuildMapper;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProjectionJob;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchRebuildJob;
import com.ecommerce.catalog.infrastructure.search.SearchIndexUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "ecommerce.catalog.search.enabled=true",
        "ecommerce.catalog.search.max-attempts=2",
        "ecommerce.catalog.search.retry-delay=1ms",
        "ecommerce.catalog.search.reconciliation-limit=2",
        "ecommerce.catalog.search.projection-initial-delay=3600000",
        "ecommerce.catalog.search.rebuild-initial-delay=3600000",
        "ecommerce.catalog.search.reconciliation-enabled=false"
})
class CatalogSearchFlowIntegrationTest {

    private static final long CATEGORY_ID = 9101;
    private static final long BRAND_ID = 9102;
    private static final long PRODUCT_ID = 9103;
    private static final long SKU_ID = 9104;

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final CatalogService catalogService;
    private final CatalogSearchOutboxService outboxService;
    private final CatalogSearchProjectionJob projectionJob;
    private final CatalogSearchRebuildService rebuildService;
    private final CatalogSearchRebuildCutoverService rebuildCutoverService;
    private final CatalogSearchRebuildJob rebuildJob;
    private final CatalogSearchReconciliationService reconciliationService;
    private final SearchOutboxMapper searchOutboxMapper;
    private final SearchReconciliationMapper searchReconciliationMapper;
    private final SearchRebuildMapper searchRebuildMapper;

    @MockitoBean
    private ProductSearchIndex index;

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    CatalogSearchFlowIntegrationTest(
            MockMvc mockMvc,
            JdbcTemplate jdbcTemplate,
            CatalogService catalogService,
            CatalogSearchOutboxService outboxService,
            CatalogSearchProjectionJob projectionJob,
            CatalogSearchRebuildService rebuildService,
            CatalogSearchRebuildCutoverService rebuildCutoverService,
            CatalogSearchRebuildJob rebuildJob,
            CatalogSearchReconciliationService reconciliationService,
            SearchOutboxMapper searchOutboxMapper,
            SearchReconciliationMapper searchReconciliationMapper,
            SearchRebuildMapper searchRebuildMapper) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
        this.outboxService = outboxService;
        this.projectionJob = projectionJob;
        this.rebuildService = rebuildService;
        this.rebuildCutoverService = rebuildCutoverService;
        this.rebuildJob = rebuildJob;
        this.reconciliationService = reconciliationService;
        this.searchOutboxMapper = searchOutboxMapper;
        this.searchReconciliationMapper = searchReconciliationMapper;
        this.searchRebuildMapper = searchRebuildMapper;
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM catalog_search_rebuild_recovery_audit");
        jdbcTemplate.update("DELETE FROM catalog_search_rebuild");
        jdbcTemplate.update("DELETE FROM catalog_search_recovery_audit");
        jdbcTemplate.update("DELETE FROM catalog_search_outbox");
        jdbcTemplate.update("DELETE FROM catalog_search_reconciliation");
        jdbcTemplate.update("DELETE FROM product_media");
        jdbcTemplate.update("DELETE FROM product_sku");
        jdbcTemplate.update("DELETE FROM product_spu");
        jdbcTemplate.update("DELETE FROM catalog_brand");
        jdbcTemplate.update("DELETE FROM catalog_category");
        reset(index);
    }

    @Test
    void writesProjectionOutboxAndNeverResurrectsInactiveProductsFromAStaleIndex() throws Exception {
        insertProduct("DRAFT");
        catalogService.publishProduct(PRODUCT_ID, 0);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT search_revision FROM product_spu WHERE id = ?",
                Long.class,
                PRODUCT_ID)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_search_outbox WHERE product_id = ? AND status = 'PENDING'",
                Integer.class,
                PRODUCT_ID)).isOne();

        doReturn(new SearchResult(java.util.List.of(PRODUCT_ID), 1))
                .when(index).search("通勤", null, 0, 20);
        mockMvc.perform(get("/api/v1/catalog/search/products")
                        .param("q", "通勤"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("OPENSEARCH"))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PRODUCT_ID)));

        doThrow(new SearchIndexUnavailableException("fault injected"))
                .when(index).search("通勤", null, 0, 20);
        mockMvc.perform(get("/api/v1/catalog/search/products")
                        .param("q", "通勤"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MYSQL_FALLBACK"))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(PRODUCT_ID)));

        catalogService.unpublishProduct(PRODUCT_ID, 1);
        doReturn(new SearchResult(java.util.List.of(PRODUCT_ID), 1))
                .when(index).search("通勤", null, 0, 20);
        mockMvc.perform(get("/api/v1/catalog/search/products")
                        .param("q", "通勤"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedTotal").value(1))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void exhaustsProjectionRetryAndRequiresIdempotentAuditedRecovery() throws Exception {
        insertProduct("DRAFT");
        catalogService.publishProduct(PRODUCT_ID, 0);
        String outboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM catalog_search_outbox WHERE product_id = ?",
                String.class,
                PRODUCT_ID);

        doThrow(new SearchIndexUnavailableException("fault injected"))
                .when(index).upsert(any());
        projectionJob.projectPending();
        Thread.sleep(10);
        projectionJob.projectPending();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM catalog_search_outbox WHERE id = ?",
                String.class,
                outboxId)).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM catalog_search_outbox WHERE id = ?",
                Integer.class,
                outboxId)).isEqualTo(2);
        verify(index, times(2)).upsert(any());

        var concurrent = runConcurrently(
                8,
                () -> outboxService.recover(
                        outboxId,
                        7001,
                        "search-recovery-1",
                        "OpenSearch 已恢复，重新投影商品"));
        assertThat(concurrent).allMatch(result -> result.equals(concurrent.get(0)));
        var recovered = concurrent.get(0);
        var replayed = outboxService.recover(
                outboxId, 7001, "search-recovery-1", "OpenSearch 已恢复，重新投影商品");
        assertThat(recovered).isEqualTo(replayed);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM catalog_search_outbox WHERE id = ?",
                String.class,
                outboxId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_search_recovery_audit WHERE command_id = 'search-recovery-1'",
                Integer.class)).isOne();

        assertThatThrownBy(() -> outboxService.recover(
                outboxId, 7001, "search-recovery-1", "不同原因不能复用同一命令"))
                .isInstanceOf(CatalogException.class)
                .extracting(error -> ((CatalogException) error).error())
                .isEqualTo(CatalogError.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void staleProjectionSelectionCannotBypassRetryDelayOrAttemptsFence() {
        insertProduct("DRAFT");
        catalogService.publishProduct(PRODUCT_ID, 0);
        Instant firstClaimedAt = Instant.now().plusSeconds(1);
        SearchOutboxEntity staleSelection = searchOutboxMapper
                .selectDispatchable(firstClaimedAt, 10)
                .get(0);

        assertThat(searchOutboxMapper.claim(
                staleSelection.getId(),
                "catalog-owner-a",
                staleSelection.getAttempts(),
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30))).isEqualTo(1);
        Instant nextAttemptAt = firstClaimedAt.plusSeconds(60);
        assertThat(searchOutboxMapper.markFailed(
                staleSelection.getId(),
                "catalog-owner-a",
                2,
                nextAttemptAt,
                "fault injected",
                firstClaimedAt.plusSeconds(1))).isEqualTo(1);

        assertThat(searchOutboxMapper.claim(
                staleSelection.getId(),
                "catalog-owner-b",
                staleSelection.getAttempts(),
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(32))).isZero();
        assertThat(searchOutboxMapper.claim(
                staleSelection.getId(),
                "catalog-owner-b",
                staleSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isZero();

        SearchOutboxEntity freshSelection = searchOutboxMapper
                .selectDispatchable(nextAttemptAt.plusSeconds(1), 10)
                .get(0);
        assertThat(freshSelection.getAttempts()).isEqualTo(1);
        assertThat(searchOutboxMapper.claim(
                freshSelection.getId(),
                "catalog-owner-b",
                freshSelection.getAttempts(),
                nextAttemptAt.plusSeconds(1),
                nextAttemptAt.plusSeconds(31))).isEqualTo(1);
        assertThat(searchOutboxMapper.markFailed(
                freshSelection.getId(),
                "catalog-owner-b",
                2,
                nextAttemptAt.plusSeconds(60),
                "fault injected again",
                nextAttemptAt.plusSeconds(2))).isEqualTo(1);

        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT status, attempts FROM catalog_search_outbox WHERE id = ?
                """, freshSelection.getId());
        assertThat(state.get("status")).isEqualTo("NEEDS_ATTENTION");
        assertThat(((Number) state.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void staleRebuildSelectionCannotConsumeRetryBudgetTwice() {
        SearchRebuildView submitted = rebuildService.submit(
                7001, "stale-rebuild-selection", "验证重建抢占版本围栏");
        SearchRebuildEntity staleSelection = searchRebuildMapper.selectPending(1).get(0);
        Instant firstClaimedAt = Instant.now().plusSeconds(1);

        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-a",
                staleSelection.getAttempts(),
                "search-test-run-a",
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30))).isEqualTo(1);
        assertThat(searchRebuildMapper.markFailed(
                submitted.id(),
                "rebuild-owner-a",
                2,
                "fault injected",
                firstClaimedAt.plusSeconds(1))).isEqualTo(1);

        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-b",
                staleSelection.getAttempts(),
                "search-test-stale-run-b",
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(32))).isZero();

        SearchRebuildEntity freshSelection = searchRebuildMapper.selectPending(1).get(0);
        assertThat(freshSelection.getAttempts()).isEqualTo(1);
        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-b",
                freshSelection.getAttempts(),
                "search-test-run-b",
                firstClaimedAt.plusSeconds(2),
                firstClaimedAt.plusSeconds(32))).isEqualTo(1);
        assertThat(searchRebuildMapper.markFailed(
                submitted.id(),
                "rebuild-owner-b",
                2,
                "fault injected again",
                firstClaimedAt.plusSeconds(3))).isEqualTo(1);

        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT status, attempts FROM catalog_search_rebuild WHERE id = ?
                """, submitted.id());
        assertThat(state.get("status")).isEqualTo("NEEDS_ATTENTION");
        assertThat(((Number) state.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void staleRebuildOwnerCannotCutOverAnotherClaimsWorkingIndex() {
        SearchRebuildView submitted = rebuildService.submit(
                7001, "stale-rebuild-cutover", "验证重建切换围栏");
        SearchRebuildEntity first = searchRebuildMapper.selectPending(1).get(0);
        Instant firstClaimedAt = Instant.now().minusSeconds(60);
        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-a",
                first.getAttempts(),
                "search-working-a",
                firstClaimedAt,
                firstClaimedAt.plusSeconds(30))).isEqualTo(1);
        assertThat(searchRebuildMapper.resetStaleClaims(Instant.now())).isEqualTo(1);

        SearchRebuildEntity second = searchRebuildMapper.selectPending(1).get(0);
        Instant secondClaimedAt = Instant.now();
        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-b",
                second.getAttempts(),
                "search-working-b",
                secondClaimedAt,
                secondClaimedAt.plusSeconds(30))).isEqualTo(1);

        assertThat(rebuildCutoverService.cutover(
                submitted.id(), "rebuild-owner-a", "search-working-a", 1)).isFalse();
        verify(index, never()).replaceAlias("search-working-a");

        assertThat(rebuildCutoverService.cutover(
                submitted.id(), "rebuild-owner-b", "search-working-b", 1)).isTrue();
        verify(index).replaceAlias("search-working-b");
        assertThat(rebuildService.get(submitted.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aliasCutoverConvergesWhenRemoteSwitchSucceededButResponseWasLost() {
        SearchRebuildView submitted = rebuildService.submit(
                7001, "unknown-result-rebuild-cutover", "验证别名切换结果未知恢复");
        SearchRebuildEntity pending = searchRebuildMapper.selectPending(1).get(0);
        Instant claimedAt = Instant.now();
        String workingIndex = "search-working-unknown-result";
        assertThat(searchRebuildMapper.claim(
                submitted.id(),
                "rebuild-owner-a",
                pending.getAttempts(),
                workingIndex,
                claimedAt,
                claimedAt.plusSeconds(30))).isEqualTo(1);

        doThrow(new SearchIndexUnavailableException("response lost after alias switch"))
                .when(index).replaceAlias(workingIndex);
        when(index.aliasTargets(workingIndex)).thenReturn(true);

        assertThat(rebuildCutoverService.cutover(
                submitted.id(),
                "rebuild-owner-a",
                workingIndex,
                7)).isTrue();
        verify(index).aliasTargets(workingIndex);
        SearchRebuildView completed = rebuildService.get(submitted.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.indexedCount()).isEqualTo(7);
    }

    @Test
    void oneConcurrentRecoveryCommandCannotMutateTwoSearchOutboxTargets() throws Exception {
        insertProduct("DRAFT");
        catalogService.publishProduct(PRODUCT_ID, 0);
        String firstOutboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM catalog_search_outbox WHERE product_id = ?",
                String.class,
                PRODUCT_ID);
        String secondOutboxId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO catalog_search_outbox
                    (id, product_id, target_revision, status, attempts, next_attempt_at,
                     claimed_at, claim_owner, claim_until, published_at, last_error,
                     created_at, updated_at)
                SELECT ?, product_id, target_revision, 'NEEDS_ATTENTION', 2, next_attempt_at,
                       NULL, NULL, NULL, NULL, 'fault injected', created_at, updated_at
                FROM catalog_search_outbox
                WHERE id = ?
                """, secondOutboxId, firstOutboxId);
        jdbcTemplate.update("""
                UPDATE catalog_search_outbox
                SET status = 'NEEDS_ATTENTION', attempts = 2, last_error = 'fault injected'
                WHERE id = ?
                """, firstOutboxId);

        List<Object> outcomes = runConcurrently(
                2,
                new java.util.concurrent.atomic.AtomicInteger()::incrementAndGet,
                index -> {
                    String target = index == 1 ? firstOutboxId : secondOutboxId;
                    try {
                        return outboxService.recover(
                                target,
                                7001,
                                "shared-cross-target-command",
                                "OpenSearch 恢复后重新投影");
                    } catch (CatalogException exception) {
                        return exception.error();
                    }
                });

        assertThat(outcomes.stream()
                .filter(CatalogError.IDEMPOTENCY_CONFLICT::equals)
                .count()).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_search_outbox
                WHERE id IN (?, ?) AND status = 'PENDING'
                """, Integer.class, firstOutboxId, secondOutboxId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_search_outbox
                WHERE id IN (?, ?) AND status = 'NEEDS_ATTENTION'
                """, Integer.class, firstOutboxId, secondOutboxId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_search_recovery_audit
                WHERE command_id = 'shared-cross-target-command'
                """, Integer.class)).isOne();
    }

    @Test
    void concurrentRebuildSubmissionAndRecoveryConvergeToSingleFacts() throws Exception {
        List<SearchRebuildView> submitted = runConcurrently(
                8,
                () -> rebuildService.submit(
                        7001,
                        "concurrent-search-rebuild",
                        "并发提交完整商品搜索重建"));
        assertThat(submitted)
                .extracting(SearchRebuildView::id)
                .containsOnly(submitted.get(0).id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_search_rebuild
                WHERE command_id = 'concurrent-search-rebuild'
                """, Integer.class)).isOne();

        long rebuildId = submitted.get(0).id();
        jdbcTemplate.update("""
                UPDATE catalog_search_rebuild
                SET status = 'NEEDS_ATTENTION', attempts = 2, last_error = 'fault injected'
                WHERE id = ?
                """, rebuildId);
        var recovered = runConcurrently(
                8,
                () -> rebuildService.recover(
                        rebuildId,
                        7001,
                        "concurrent-rebuild-recovery",
                        "OpenSearch 恢复后重新执行重建"));
        assertThat(recovered).allMatch(result -> result.equals(recovered.get(0)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM catalog_search_rebuild WHERE id = ?",
                String.class,
                rebuildId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_search_rebuild_recovery_audit
                WHERE command_id = 'concurrent-rebuild-recovery'
                """, Integer.class)).isOne();
    }

    @Test
    void rebuildsByAliasAndReconcilesMissingStaleAndOrphanDocuments() {
        insertProduct("ACTIVE");
        when(index.scanVersions(anyInt())).thenReturn(Map.of(PRODUCT_ID, 1L));

        SearchRebuildView submitted = rebuildService.submit(
                7001, "search-rebuild-1", "首次建立完整商品搜索投影");
        rebuildJob.rebuildPending();

        SearchRebuildView completed = rebuildService.get(submitted.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.indexedCount()).isOne();
        assertThat(completed.targetIndex()).contains("-run-");
        verify(index).createIndex(completed.targetIndex());
        verify(index).bulkIndex(eq(completed.targetIndex()), anyList());
        verify(index).replaceAlias(completed.targetIndex());

        when(index.scanVersions(anyInt())).thenReturn(Map.of(
                PRODUCT_ID, 0L,
                9999L, 3L));
        SearchReconciliationResult divergent = reconciliationService.reconcile(true);
        assertThat(divergent.stale()).isOne();
        assertThat(divergent.orphan()).isOne();
        assertThat(divergent.repairEvents()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_search_reconciliation WHERE status = 'OPEN'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_search_outbox",
                Integer.class)).isEqualTo(2);

        when(index.scanVersions(anyInt())).thenReturn(Map.of(PRODUCT_ID, 1L));
        SearchReconciliationResult converged = reconciliationService.reconcile(false);
        assertThat(converged.missing()).isZero();
        assertThat(converged.stale()).isZero();
        assertThat(converged.orphan()).isZero();
        assertThat(converged.resolved()).isEqualTo(2);
    }

    @Test
    void neverRepairsAnUnscannedTailAsAnOrphanWhenReconciliationIsSaturated() {
        insertProduct("ACTIVE");
        insertAdditionalProduct(9203, 9204, "第二个搜索商品");
        insertAdditionalProduct(9303, 9304, "第三个搜索商品");
        when(index.scanVersions(anyInt())).thenReturn(Map.of(
                9203L, 1L,
                9303L, 1L,
                9403L, 7L));

        SearchReconciliationResult result = reconciliationService.reconcile(true);

        assertThat(result.saturated()).isTrue();
        assertThat(result.missing()).isOne();
        assertThat(result.orphan()).isZero();
        assertThat(result.repairEvents()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_search_outbox WHERE product_id = 9403",
                Integer.class)).isZero();
    }

    @Test
    void staleSearchScanCannotResolveAFindingRefreshedByAnotherScanner() {
        Instant firstDetectedAt = Instant.parse("2026-07-25T01:00:00Z");
        assertThat(searchReconciliationMapper.insertIfAbsent(
                9901L, PRODUCT_ID, "STALE", 2L, 1L, firstDetectedAt)).isOne();
        SearchReconciliationEntity stale =
                searchReconciliationMapper.selectByStatus("OPEN", 10).get(0);

        Instant refreshedAt = firstDetectedAt.plusMillis(1);
        assertThat(searchReconciliationMapper.touchOpen(
                stale.getProductId(), stale.getIssueType(), 3L, 1L, refreshedAt)).isOne();

        assertThat(searchReconciliationMapper.markResolved(
                stale.getId(), stale.getOccurrences(), stale.getLastDetectedAt(),
                refreshedAt.plusMillis(1))).isZero();
        SearchReconciliationEntity current =
                searchReconciliationMapper.selectByStatus("OPEN", 10).get(0);
        assertThat(current.getOccurrences()).isEqualTo(2);
        assertThat(current.getMysqlRevision()).isEqualTo(3L);
        assertThat(current.getLastDetectedAt()).isEqualTo(refreshedAt);
    }

    @Test
    void rejectsSearchOffsetsBeyondTheExplicitOpenSearchWindow() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/search/products")
                        .param("q", "通勤")
                        .param("page", "501")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private void insertProduct(String status) {
        jdbcTemplate.update("""
                INSERT INTO catalog_category
                    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
                VALUES (?, NULL, '搜索分类', 'search-category', 'ACTIVE', 1, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, CATEGORY_ID);
        jdbcTemplate.update("""
                INSERT INTO catalog_brand
                    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
                VALUES (?, '搜索品牌', 'search-brand', NULL, 'ACTIVE', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, BRAND_ID);
        jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, category_id, brand_id, title, subtitle, description, status, version,
                     search_revision, created_at, updated_at)
                VALUES (?, ?, ?, '通勤收纳包', '适合每日通勤', '轻量、防泼水、可收纳', ?, 0,
                        1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, PRODUCT_ID, CATEGORY_ID, BRAND_ID, status);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, name, spec_json, sale_price, market_price, status,
                     version, created_at, updated_at)
                VALUES (?, ?, 'SEARCH-SKU-1', '青灰色', '{"color":"青灰"}', 99.00, 129.00,
                        'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, SKU_ID, PRODUCT_ID);
    }

    private void insertAdditionalProduct(long productId, long skuId, String title) {
        jdbcTemplate.update("""
                INSERT INTO product_spu
                    (id, category_id, brand_id, title, subtitle, description, status, version,
                     search_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, '搜索对账边界', '用于饱和扫描安全性验证', 'ACTIVE', 0,
                        1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, productId, CATEGORY_ID, BRAND_ID, title);
        jdbcTemplate.update("""
                INSERT INTO product_sku
                    (id, spu_id, sku_code, name, spec_json, sale_price, market_price, status,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, '青灰色', '{"color":"青灰"}', 99.00, 129.00,
                        'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, skuId, productId, "SEARCH-SKU-" + productId);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action)
            throws Exception {
        return runConcurrently(participants, () -> 0, ignored -> action.call());
    }

    private <T> List<T> runConcurrently(
            int participants,
            Callable<Integer> sequence,
            CheckedFunction<Integer, T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, participants)
                    .mapToObj(ignored -> executor.submit(() -> {
                        int index = sequence.call();
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Concurrent catalog search test start timed out");
                        }
                        return action.apply(index);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(
                            "Concurrent catalog search action failed",
                            exception);
                }
            }).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface CheckedFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
