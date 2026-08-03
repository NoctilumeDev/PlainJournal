package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import com.ecommerce.trade.infrastructure.reconciliation.ReconciliationFinding;
import com.ecommerce.trade.infrastructure.reconciliation.TradeReconciliationProperties;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TradeReconciliationService {

    private final ReconciliationRecordMapper mapper;
    private final TradeReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final TradeShardRouter shardRouter;

    public TradeReconciliationService(
            ReconciliationRecordMapper mapper,
            TradeReconciliationProperties properties,
            TransactionTemplate transactionTemplate,
            TradeShardRouter shardRouter) {
        this.mapper = mapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.shardRouter = shardRouter;
    }

    public ReconciliationScanResult reconcileNow() {
        int findings = 0;
        int opened = 0;
        int resolved = 0;
        boolean saturated = false;
        for (int shardIndex = 0; shardIndex < shardRouter.shardCount(); shardIndex++) {
            ReconciliationScanResult result = shardRouter.executeOnShard(
                    shardIndex,
                    () -> Objects.requireNonNull(
                            transactionTemplate.execute(ignored -> reconcileInTransaction())));
            findings += result.findings();
            opened += result.opened();
            resolved += result.resolved();
            saturated |= result.saturated();
        }
        return new ReconciliationScanResult(findings, opened, resolved, saturated);
    }

    public List<ReconciliationIssueView> listIssues(String status, int limit) {
        List<ShardIssue> candidates = new ArrayList<>();
        for (int shardIndex = 0; shardIndex < shardRouter.shardCount(); shardIndex++) {
            int selectedShard = shardIndex;
            List<ReconciliationRecordEntity> shardIssues = shardRouter.executeOnShard(
                    shardIndex,
                    () -> mapper.selectByStatus(status, limit));
            shardIssues.forEach(issue -> candidates.add(new ShardIssue(selectedShard, issue)));
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparing(
                                (ShardIssue issue) -> issue.record().getLastDetectedAt(),
                                Comparator.reverseOrder())
                        .thenComparing(
                                issue -> issue.record().getId(),
                                Comparator.reverseOrder())
                        .thenComparingInt(ShardIssue::shardIndex))
                .limit(limit)
                .map(issue -> view(issue.record()))
                .toList();
    }

    public long countOpenIssues() {
        long count = 0;
        for (int shardIndex = 0; shardIndex < shardRouter.shardCount(); shardIndex++) {
            count += shardRouter.executeOnShard(shardIndex, mapper::countOpen);
        }
        return count;
    }

    private ReconciliationScanResult reconcileInTransaction() {
        int scanLimit = properties.scanLimit();
        List<ReconciliationFinding> selected = mapper.selectFindings(scanLimit + 1);
        boolean findingsSaturated = selected.size() > scanLimit;
        List<ReconciliationFinding> findings = findingsSaturated
                ? selected.subList(0, scanLimit) : selected;
        Instant now = mapper.currentTime();
        Set<String> activeKeys = new HashSet<>();
        int opened = 0;
        for (ReconciliationFinding finding : findings) {
            activeKeys.add(finding.key());
            ReconciliationRecordEntity candidate = new ReconciliationRecordEntity();
            candidate.setId(IdWorker.getId());
            candidate.setDomain(finding.domain());
            candidate.setReferenceNo(finding.referenceNo());
            candidate.setIssueType(finding.issueType());
            candidate.setStatus("OPEN");
            candidate.setOccurrences(1);
            candidate.setFirstDetectedAt(now);
            candidate.setLastDetectedAt(now);
            if (mapper.insertIfAbsent(candidate) == 1) {
                opened++;
            } else {
                mapper.touchOpen(finding.domain(), finding.referenceNo(), finding.issueType(), now);
            }
        }
        List<ReconciliationRecordEntity> selectedOpen = mapper.selectByStatus("OPEN", scanLimit + 1);
        boolean openSaturated = selectedOpen.size() > scanLimit;
        int resolved = 0;
        if (!findingsSaturated && !openSaturated) {
            for (ReconciliationRecordEntity issue : selectedOpen) {
                if (!activeKeys.contains(key(issue)) && mapper.markResolved(
                        issue.getId(),
                        issue.getOccurrences(),
                        issue.getLastDetectedAt(),
                        now) == 1) {
                    resolved++;
                }
            }
        }
        return new ReconciliationScanResult(findings.size(), opened, resolved,
                findingsSaturated || openSaturated);
    }

    private String key(ReconciliationRecordEntity issue) {
        return issue.getDomain() + "\u0000" + issue.getReferenceNo() + "\u0000" + issue.getIssueType();
    }

    private ReconciliationIssueView view(ReconciliationRecordEntity issue) {
        return new ReconciliationIssueView(
                issue.getDomain(), issue.getReferenceNo(), issue.getIssueType(), issue.getStatus(),
                issue.getOccurrences(), issue.getFirstDetectedAt(), issue.getLastDetectedAt(),
                issue.getResolvedAt());
    }

    private record ShardIssue(int shardIndex, ReconciliationRecordEntity record) {
    }

    public record ReconciliationScanResult(int findings, int opened, int resolved, boolean saturated) {
    }

    public record ReconciliationIssueView(
            String domain, String referenceNo, String issueType, String status, int occurrences,
            Instant firstDetectedAt, Instant lastDetectedAt, Instant resolvedAt) {
    }
}
