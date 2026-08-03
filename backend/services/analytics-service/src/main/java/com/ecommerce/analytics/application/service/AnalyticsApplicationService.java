package com.ecommerce.analytics.application.service;

import com.ecommerce.analytics.application.exception.AnalyticsError;
import com.ecommerce.analytics.application.exception.AnalyticsException;
import com.ecommerce.analytics.application.model.AnalyticsModels.DailySummary;
import com.ecommerce.analytics.application.model.AnalyticsModels.DashboardView;
import com.ecommerce.analytics.application.model.AnalyticsModels.DomainEvent;
import com.ecommerce.analytics.application.model.AnalyticsModels.OverviewTotals;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductContribution;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProductLine;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProjectionFreshness;
import com.ecommerce.analytics.application.model.AnalyticsModels.ProjectionIssue;
import com.ecommerce.analytics.application.model.AnalyticsModels.RebuildView;
import com.ecommerce.analytics.application.model.AnalyticsModels.ReconciliationView;
import com.ecommerce.analytics.infrastructure.config.AnalyticsProperties;
import com.ecommerce.analytics.infrastructure.observability.AnalyticsObservability;
import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository;
import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository.ProductProjectionRow;
import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository.ProjectionRows;
import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository.SourceIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AnalyticsApplicationService {

    private static final int DEFAULT_TOP_PRODUCT_LIMIT = 10;
    private static final int MAX_TOP_PRODUCT_LIMIT = 100;
    private static final int MAX_RETURNED_ISSUES = 200;

    private final AnalyticsRepository repository;
    private final AnalyticsProperties properties;
    private final AnalyticsObservability observability;

    public AnalyticsApplicationService(
            AnalyticsRepository repository,
            AnalyticsProperties properties,
            AnalyticsObservability observability) {
        this.repository = repository;
        this.properties = properties;
        this.observability = observability;
    }

    @Transactional
    public boolean acceptDomainEvent(DomainEvent event, String consumerGroup) {
        repository.lockProjection();
        SourceIdentity existing = repository.findSourceByEventId(event.eventId());
        if (existing == null) {
            existing = repository.findSourceByLogicalIdentity(event);
        }
        if (existing != null) {
            requireSameEvent(existing, event);
            observability.eventDuplicate(event.eventType());
            return false;
        }

        Instant now = repository.currentTime();
        LocalDate businessDate = event.occurredAt()
                .atZone(properties.businessZone())
                .toLocalDate();
        repository.insertSourceEvent(event, consumerGroup, businessDate, now);
        for (ProductLine line : event.productLines()) {
            repository.insertProductLine(event.eventId(), line);
        }
        repository.incrementDaily(businessDate, event.eventType(), event.amount(), now);
        if ("OrderCompleted".equals(event.eventType())) {
            for (ProductContribution contribution : contributions(event.productLines())) {
                repository.incrementProduct(businessDate, contribution, now);
            }
        }
        observability.eventAccepted(event.eventType());
        return true;
    }

    public DashboardView dashboard(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            Integer requestedProductLimit) {
        DateRange range = dateRange(requestedFrom, requestedTo);
        int productLimit = requestedProductLimit == null
                ? DEFAULT_TOP_PRODUCT_LIMIT
                : Math.max(1, Math.min(MAX_TOP_PRODUCT_LIMIT, requestedProductLimit));
        List<DailySummary> daily = repository.selectDaily(range.from(), range.to());
        OverviewTotals totals = totals(daily, repository.countUniqueCustomers(
                range.from(),
                range.to()));
        Instant generatedAt = repository.currentTime();
        return new DashboardView(
                range.from(),
                range.to(),
                totals,
                daily,
                repository.selectTopProducts(range.from(), range.to(), productLimit),
                new ProjectionFreshness(
                        repository.countSourceEvents(range.from(), range.to()),
                        repository.selectLastConsumedAt(range.from(), range.to()),
                        generatedAt));
    }

    public ReconciliationView reconcile(LocalDate requestedFrom, LocalDate requestedTo) {
        DateRange range = dateRange(requestedFrom, requestedTo);
        ReconciliationView result = reconcileRange(range);
        observability.reconciliationCompleted(result.issueCount());
        return result;
    }

    @Transactional
    public RebuildView rebuild(
            long operatorId,
            String commandId,
            String reason,
            LocalDate requestedFrom,
            LocalDate requestedTo) {
        DateRange range = dateRange(requestedFrom, requestedTo);
        String normalizedCommandId = requireText(commandId, 64, "commandId");
        String normalizedReason = requireReason(reason);
        String requestHash = requestHash(
                operatorId,
                normalizedCommandId,
                normalizedReason,
                range);

        repository.lockProjection();
        var existing = repository.findRebuildAudit(normalizedCommandId);
        if (existing != null) {
            if (!MessageDigest.isEqual(
                    existing.requestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                throw new AnalyticsException(AnalyticsError.IDEMPOTENCY_CONFLICT);
            }
            return existing.view();
        }

        try {
            ReconciliationView before = reconcileRange(range);
            if (before.saturated()) {
                throw new AnalyticsException(AnalyticsError.RECONCILIATION_SATURATED);
            }
            long sourceEventCount = repository.countSourceEvents(range.from(), range.to());
            repository.rebuildRange(range.from(), range.to());
            ReconciliationView after = reconcileRange(range);
            if (after.saturated()) {
                throw new AnalyticsException(AnalyticsError.RECONCILIATION_SATURATED);
            }
            if (after.issueCount() != 0) {
                throw new AnalyticsException(AnalyticsError.REBUILD_DID_NOT_CONVERGE);
            }

            Instant now = repository.currentTime();
            repository.insertRebuildAudit(
                    normalizedCommandId,
                    requestHash,
                    operatorId,
                    normalizedReason,
                    range.from(),
                    range.to(),
                    sourceEventCount,
                    before.issueCount(),
                    after.issueCount(),
                    now);
            observability.reconciliationCompleted(0);
            observability.rebuildCompleted(true);
            var persisted = repository.findRebuildAudit(normalizedCommandId);
            if (persisted == null) {
                throw new AnalyticsException(AnalyticsError.REBUILD_DID_NOT_CONVERGE);
            }
            return persisted.view();
        } catch (RuntimeException exception) {
            observability.rebuildCompleted(false);
            throw exception;
        }
    }

    private ReconciliationView reconcileRange(DateRange range) {
        int limit = properties.reconciliationRowLimit();
        ProjectionRows<DailySummary> expectedDaily =
                repository.selectExpectedDaily(range.from(), range.to(), limit);
        ProjectionRows<DailySummary> actualDaily =
                repository.selectActualDaily(range.from(), range.to(), limit);
        ProjectionRows<ProductProjectionRow> expectedProducts =
                repository.selectExpectedProducts(range.from(), range.to(), limit);
        ProjectionRows<ProductProjectionRow> actualProducts =
                repository.selectActualProducts(range.from(), range.to(), limit);

        IssueCollector collector = new IssueCollector();
        compareDaily(expectedDaily.rows(), actualDaily.rows(), collector);
        compareProducts(expectedProducts.rows(), actualProducts.rows(), collector);
        boolean saturated = expectedDaily.saturated()
                || actualDaily.saturated()
                || expectedProducts.saturated()
                || actualProducts.saturated();
        return new ReconciliationView(
                range.from(),
                range.to(),
                Math.max(expectedDaily.rows().size(), actualDaily.rows().size()),
                Math.max(expectedProducts.rows().size(), actualProducts.rows().size()),
                collector.issueCount(),
                saturated,
                collector.issues(),
                repository.currentTime());
    }

    private void compareDaily(
            List<DailySummary> expectedRows,
            List<DailySummary> actualRows,
            IssueCollector collector) {
        Map<LocalDate, DailySummary> expected = byDate(expectedRows);
        Map<LocalDate, DailySummary> actual = byDate(actualRows);
        Set<LocalDate> keys = new LinkedHashSet<>();
        keys.addAll(expected.keySet().stream().sorted().toList());
        keys.addAll(actual.keySet().stream().sorted().toList());
        for (LocalDate key : keys.stream().sorted().toList()) {
            DailySummary expectedRow = expected.get(key);
            DailySummary actualRow = actual.get(key);
            if (expectedRow == null) {
                collector.add("DAILY", "ORPHAN", key.toString(), null, dailyFacts(actualRow));
            } else if (actualRow == null) {
                collector.add("DAILY", "MISSING", key.toString(), dailyFacts(expectedRow), null);
            } else if (!sameDailyFacts(expectedRow, actualRow)) {
                collector.add(
                        "DAILY",
                        "STALE",
                        key.toString(),
                        dailyFacts(expectedRow),
                        dailyFacts(actualRow));
            }
        }
    }

    private void compareProducts(
            List<ProductProjectionRow> expectedRows,
            List<ProductProjectionRow> actualRows,
            IssueCollector collector) {
        Map<ProductKey, ProductProjectionRow> expected = byProduct(expectedRows);
        Map<ProductKey, ProductProjectionRow> actual = byProduct(actualRows);
        Set<ProductKey> keys = new LinkedHashSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());
        for (ProductKey key : keys.stream().sorted().toList()) {
            ProductProjectionRow expectedRow = expected.get(key);
            ProductProjectionRow actualRow = actual.get(key);
            String displayKey = key.businessDate() + ":" + key.productId();
            if (expectedRow == null) {
                collector.add("PRODUCT", "ORPHAN", displayKey, null, productFacts(actualRow));
            } else if (actualRow == null) {
                collector.add("PRODUCT", "MISSING", displayKey, productFacts(expectedRow), null);
            } else if (!sameProductFacts(expectedRow, actualRow)) {
                collector.add(
                        "PRODUCT",
                        "STALE",
                        displayKey,
                        productFacts(expectedRow),
                        productFacts(actualRow));
            }
        }
    }

    private List<ProductContribution> contributions(List<ProductLine> lines) {
        Map<Long, MutableProductContribution> grouped = new HashMap<>();
        for (ProductLine line : lines) {
            MutableProductContribution contribution = grouped.computeIfAbsent(
                    line.productId(),
                    ignored -> new MutableProductContribution(
                            line.productId(),
                            line.productTitle()));
            contribution.add(line);
        }
        return grouped.values().stream()
                .sorted(Comparator.comparingLong(MutableProductContribution::productId))
                .map(MutableProductContribution::view)
                .toList();
    }

    private OverviewTotals totals(List<DailySummary> rows, long uniqueCustomers) {
        return new OverviewTotals(
                rows.stream().mapToLong(DailySummary::createdOrderCount).sum(),
                sum(rows, DailySummary::createdOrderAmount),
                rows.stream().mapToLong(DailySummary::paymentCount).sum(),
                sum(rows, DailySummary::paymentAmount),
                rows.stream().mapToLong(DailySummary::completedOrderCount).sum(),
                sum(rows, DailySummary::completedOrderAmount),
                rows.stream().mapToLong(DailySummary::closedOrderCount).sum(),
                rows.stream().mapToLong(DailySummary::afterSaleCount).sum(),
                sum(rows, DailySummary::afterSaleAmount),
                rows.stream().mapToLong(DailySummary::refundCount).sum(),
                sum(rows, DailySummary::refundAmount),
                uniqueCustomers);
    }

    private BigDecimal sum(
            List<DailySummary> rows,
            java.util.function.Function<DailySummary, BigDecimal> extractor) {
        return rows.stream()
                .map(extractor)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private DateRange dateRange(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = repository.currentTime().atZone(properties.businessZone()).toLocalDate();
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(29) : requestedFrom;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || days <= 0 || days > properties.maximumRangeDays()) {
            throw new AnalyticsException(AnalyticsError.INVALID_DATE_RANGE);
        }
        return new DateRange(from, to);
    }

    private String requireText(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private String requireReason(String reason) {
        String normalized = requireText(reason, 500, "reason");
        if (normalized.length() < 8) {
            throw new IllegalArgumentException("reason must contain at least 8 characters");
        }
        return normalized;
    }

    private String requestHash(
            long operatorId,
            String commandId,
            String reason,
            DateRange range) {
        if (operatorId <= 0) {
            throw new IllegalArgumentException("operatorId must be positive");
        }
        String canonical = String.join(
                "\n",
                Long.toString(operatorId),
                commandId,
                reason,
                range.from().toString(),
                range.to().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireSameEvent(SourceIdentity existing, DomainEvent event) {
        boolean same = existing.eventType().equals(event.eventType())
                && existing.producer().equals(event.producer())
                && existing.aggregateType().equals(event.aggregateType())
                && existing.aggregateId().equals(event.aggregateId())
                && existing.aggregateVersion() == event.aggregateVersion()
                && MessageDigest.isEqual(
                        existing.fingerprint().getBytes(StandardCharsets.UTF_8),
                        event.fingerprint().getBytes(StandardCharsets.UTF_8));
        if (!same) {
            throw new IllegalArgumentException(
                    "Conflicting analytics source event identity: " + event.eventId());
        }
    }

    private Map<LocalDate, DailySummary> byDate(List<DailySummary> rows) {
        Map<LocalDate, DailySummary> result = new HashMap<>();
        for (DailySummary row : rows) {
            result.put(row.businessDate(), row);
        }
        return result;
    }

    private Map<ProductKey, ProductProjectionRow> byProduct(List<ProductProjectionRow> rows) {
        Map<ProductKey, ProductProjectionRow> result = new HashMap<>();
        for (ProductProjectionRow row : rows) {
            result.put(new ProductKey(row.businessDate(), row.productId()), row);
        }
        return result;
    }

    private boolean sameDailyFacts(DailySummary left, DailySummary right) {
        return left.createdOrderCount() == right.createdOrderCount()
                && left.createdOrderAmount().compareTo(right.createdOrderAmount()) == 0
                && left.paymentCount() == right.paymentCount()
                && left.paymentAmount().compareTo(right.paymentAmount()) == 0
                && left.completedOrderCount() == right.completedOrderCount()
                && left.completedOrderAmount().compareTo(right.completedOrderAmount()) == 0
                && left.closedOrderCount() == right.closedOrderCount()
                && left.afterSaleCount() == right.afterSaleCount()
                && left.afterSaleAmount().compareTo(right.afterSaleAmount()) == 0
                && left.refundCount() == right.refundCount()
                && left.refundAmount().compareTo(right.refundAmount()) == 0;
    }

    private boolean sameProductFacts(ProductProjectionRow left, ProductProjectionRow right) {
        return left.completedOrderCount() == right.completedOrderCount()
                && left.unitsSold() == right.unitsSold()
                && left.netRevenue().compareTo(right.netRevenue()) == 0
                && left.revenueCoveredOrderCount() == right.revenueCoveredOrderCount();
    }

    private String dailyFacts(DailySummary row) {
        if (row == null) {
            return null;
        }
        return "%d/%s|%d/%s|%d/%s|%d|%d/%s|%d/%s".formatted(
                row.createdOrderCount(),
                row.createdOrderAmount(),
                row.paymentCount(),
                row.paymentAmount(),
                row.completedOrderCount(),
                row.completedOrderAmount(),
                row.closedOrderCount(),
                row.afterSaleCount(),
                row.afterSaleAmount(),
                row.refundCount(),
                row.refundAmount());
    }

    private String productFacts(ProductProjectionRow row) {
        if (row == null) {
            return null;
        }
        return "%d|%d|%s|%d".formatted(
                row.completedOrderCount(),
                row.unitsSold(),
                row.netRevenue(),
                row.revenueCoveredOrderCount());
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record ProductKey(LocalDate businessDate, long productId)
            implements Comparable<ProductKey> {

        @Override
        public int compareTo(ProductKey other) {
            int dateComparison = businessDate.compareTo(other.businessDate);
            return dateComparison != 0
                    ? dateComparison
                    : Long.compare(productId, other.productId);
        }
    }

    private static final class MutableProductContribution {

        private final long productId;
        private String productTitle;
        private long units;
        private BigDecimal netRevenue = BigDecimal.ZERO.setScale(2);
        private boolean revenueCovered = true;

        private MutableProductContribution(long productId, String productTitle) {
            this.productId = productId;
            this.productTitle = productTitle;
        }

        private void add(ProductLine line) {
            productTitle = line.productTitle();
            units = Math.addExact(units, line.quantity());
            if (line.payableAmount() == null) {
                revenueCovered = false;
            } else {
                netRevenue = netRevenue.add(line.payableAmount());
            }
        }

        private long productId() {
            return productId;
        }

        private ProductContribution view() {
            return new ProductContribution(
                    productId,
                    productTitle,
                    units,
                    revenueCovered ? netRevenue : BigDecimal.ZERO.setScale(2),
                    revenueCovered);
        }
    }

    private static final class IssueCollector {

        private final List<ProjectionIssue> issues = new ArrayList<>();
        private long issueCount;

        private void add(
                String projection,
                String issueType,
                String key,
                String expected,
                String actual) {
            issueCount++;
            if (issues.size() < MAX_RETURNED_ISSUES) {
                issues.add(new ProjectionIssue(
                        projection,
                        issueType,
                        key,
                        expected,
                        actual));
            }
        }

        private long issueCount() {
            return issueCount;
        }

        private List<ProjectionIssue> issues() {
            return List.copyOf(issues);
        }
    }
}
