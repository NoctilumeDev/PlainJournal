package com.ecommerce.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.SearchModels.SearchReconciliationIssueView;
import com.ecommerce.catalog.application.model.SearchModels.SearchReconciliationResult;
import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchProductDocument;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchReconciliationEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.SearchReconciliationMapper;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProjectionReader;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CatalogSearchReconciliationService {

    private final ProductSearchIndex index;
    private final CatalogSearchProjectionReader reader;
    private final CatalogSearchOutboxService outboxService;
    private final SearchReconciliationMapper mapper;
    private final CatalogSearchProperties properties;
    private final TransactionTemplate transactionTemplate;

    public CatalogSearchReconciliationService(
            ProductSearchIndex index,
            CatalogSearchProjectionReader reader,
            CatalogSearchOutboxService outboxService,
            SearchReconciliationMapper mapper,
            CatalogSearchProperties properties,
            TransactionTemplate transactionTemplate,
            MeterRegistry registry) {
        this.index = index;
        this.reader = reader;
        this.outboxService = outboxService;
        this.mapper = mapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        Gauge.builder("ecommerce.catalog.search.reconciliation.open",
                        mapper, SearchReconciliationMapper::countOpen)
                .register(registry);
    }

    public SearchReconciliationResult reconcile(boolean repair) {
        if (!properties.enabled()) {
            throw new CatalogException(CatalogError.SEARCH_INDEX_UNAVAILABLE);
        }
        int limit = properties.reconciliationLimit();
        Map<Long, Long> mysqlVersions = loadMysqlVersions(limit + 1);
        Map<Long, Long> indexVersions = index.scanVersions(limit + 1);
        boolean mysqlSaturated = mysqlVersions.size() > limit;
        boolean indexSaturated = indexVersions.size() > limit;
        ComparableVersions comparable = comparableVersions(
                mysqlVersions,
                indexVersions,
                mysqlSaturated,
                indexSaturated);
        boolean saturated = mysqlSaturated || indexSaturated;
        List<Finding> findings = findings(comparable.mysql(), comparable.index());
        return Objects.requireNonNull(transactionTemplate.execute(ignored ->
                persist(findings, mysqlVersions.size(), indexVersions.size(), saturated, repair)));
    }

    public List<SearchReconciliationIssueView> listIssues(String status, int limit) {
        return mapper.selectByStatus(status, limit).stream().map(this::view).toList();
    }

    private Map<Long, Long> loadMysqlVersions(int limit) {
        Map<Long, Long> result = new HashMap<>();
        long afterId = 0;
        while (result.size() < limit) {
            int remaining = limit - result.size();
            List<SearchProductDocument> batch = reader.readActiveBatch(
                    afterId, Math.min(properties.rebuildBatchSize(), remaining));
            if (batch.isEmpty()) {
                break;
            }
            for (SearchProductDocument document : batch) {
                result.put(document.productId(), document.revision());
            }
            afterId = batch.get(batch.size() - 1).productId();
        }
        return Map.copyOf(result);
    }

    private ComparableVersions comparableVersions(
            Map<Long, Long> mysqlVersions,
            Map<Long, Long> indexVersions,
            boolean mysqlSaturated,
            boolean indexSaturated) {
        Map<Long, Long> comparableMysql = new HashMap<>(mysqlVersions);
        Map<Long, Long> comparableIndex = new HashMap<>(indexVersions);
        if (indexSaturated && !indexVersions.isEmpty()) {
            long lastIndexedId = indexVersions.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
            comparableMysql.keySet().removeIf(productId -> productId > lastIndexedId);
        }
        if (mysqlSaturated && !mysqlVersions.isEmpty()) {
            long lastMysqlId = mysqlVersions.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
            comparableIndex.keySet().removeIf(productId -> productId > lastMysqlId);
        }
        return new ComparableVersions(
                Map.copyOf(comparableMysql),
                Map.copyOf(comparableIndex));
    }

    private List<Finding> findings(Map<Long, Long> mysqlVersions, Map<Long, Long> indexVersions) {
        List<Finding> result = new ArrayList<>();
        for (Map.Entry<Long, Long> mysql : mysqlVersions.entrySet()) {
            Long indexedRevision = indexVersions.get(mysql.getKey());
            if (indexedRevision == null) {
                result.add(new Finding(mysql.getKey(), "MISSING", mysql.getValue(), null));
            } else if (!mysql.getValue().equals(indexedRevision)) {
                result.add(new Finding(mysql.getKey(), "STALE", mysql.getValue(), indexedRevision));
            }
        }
        for (Map.Entry<Long, Long> indexed : indexVersions.entrySet()) {
            if (!mysqlVersions.containsKey(indexed.getKey())) {
                result.add(new Finding(indexed.getKey(), "ORPHAN", null, indexed.getValue()));
            }
        }
        return result;
    }

    private SearchReconciliationResult persist(
            List<Finding> findings,
            int mysqlDocuments,
            int indexDocuments,
            boolean saturated,
            boolean repair) {
        Instant now = mapper.currentTime();
        Set<String> active = new HashSet<>();
        int opened = 0;
        int missing = 0;
        int stale = 0;
        int orphan = 0;
        int repairEvents = 0;
        for (Finding finding : findings) {
            active.add(finding.key());
            switch (finding.issueType()) {
                case "MISSING" -> missing++;
                case "STALE" -> stale++;
                case "ORPHAN" -> orphan++;
                default -> throw new IllegalStateException("Unsupported search finding");
            }
            if (mapper.insertIfAbsent(
                    IdWorker.getId(),
                    finding.productId(),
                    finding.issueType(),
                    finding.mysqlRevision(),
                    finding.indexRevision(),
                    now) == 1) {
                opened++;
            } else {
                mapper.touchOpen(
                        finding.productId(),
                        finding.issueType(),
                        finding.mysqlRevision(),
                        finding.indexRevision(),
                        now);
            }
            if (repair) {
                long targetRevision = finding.mysqlRevision() != null
                        ? finding.mysqlRevision()
                        : finding.indexRevision() + 1;
                outboxService.enqueueRepair(finding.productId(), targetRevision);
                repairEvents++;
            }
        }

        int resolved = 0;
        List<SearchReconciliationEntity> open = mapper.selectOpen(properties.reconciliationLimit() + 1);
        if (!saturated && open.size() <= properties.reconciliationLimit()) {
            for (SearchReconciliationEntity issue : open) {
                if (!active.contains(key(issue)) && mapper.markResolved(
                        issue.getId(),
                        issue.getOccurrences(),
                        issue.getLastDetectedAt(),
                        now) == 1) {
                    resolved++;
                }
            }
        }
        return new SearchReconciliationResult(
                mysqlDocuments,
                indexDocuments,
                missing,
                stale,
                orphan,
                opened,
                resolved,
                repairEvents,
                saturated || open.size() > properties.reconciliationLimit());
    }

    private String key(SearchReconciliationEntity issue) {
        return issue.getProductId() + "\u0000" + issue.getIssueType();
    }

    private SearchReconciliationIssueView view(SearchReconciliationEntity issue) {
        return new SearchReconciliationIssueView(
                issue.getProductId(),
                issue.getIssueType(),
                issue.getStatus(),
                issue.getMysqlRevision(),
                issue.getIndexRevision(),
                issue.getOccurrences(),
                issue.getFirstDetectedAt(),
                issue.getLastDetectedAt(),
                issue.getResolvedAt());
    }

    private record Finding(
            Long productId,
            String issueType,
            Long mysqlRevision,
            Long indexRevision) {

        private String key() {
            return productId + "\u0000" + issueType;
        }
    }

    private record ComparableVersions(
            Map<Long, Long> mysql,
            Map<Long, Long> index) {
    }
}
