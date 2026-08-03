package com.ecommerce.payment.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.payment.application.model.PaymentModels.ReconciliationIssueView;
import com.ecommerce.payment.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import com.ecommerce.payment.infrastructure.reconciliation.PaymentReconciliationProperties;
import com.ecommerce.payment.infrastructure.reconciliation.ReconciliationFinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PaymentReconciliationService {

    private final ReconciliationRecordMapper mapper;
    private final PaymentReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PaymentReconciliationService(
            ReconciliationRecordMapper mapper,
            PaymentReconciliationProperties properties,
            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public ReconciliationScanResult reconcileNow() {
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> reconcileInTransaction()));
    }

    public List<ReconciliationIssueView> listIssues(String status, int limit) {
        return mapper.selectByStatus(status, limit).stream()
                .map(this::view)
                .toList();
    }

    private ReconciliationScanResult reconcileInTransaction() {
        int scanLimit = properties.scanLimit();
        List<ReconciliationFinding> selected = mapper.selectFindings(scanLimit + 1);
        boolean findingsSaturated = selected.size() > scanLimit;
        List<ReconciliationFinding> findings = findingsSaturated
                ? selected.subList(0, scanLimit)
                : selected;
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

    public record ReconciliationScanResult(
            int findings,
            int opened,
            int resolved,
            boolean saturated
    ) {
    }
}
