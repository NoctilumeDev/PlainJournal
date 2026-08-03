package com.ecommerce.analytics.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AnalyticsModels {

    private AnalyticsModels() {
    }

    public record DomainEvent(
            String eventId,
            String eventType,
            String producer,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            Instant occurredAt,
            long userId,
            String orderNo,
            BigDecimal amount,
            String fingerprint,
            List<ProductLine> productLines) {

        public DomainEvent {
            amount = money(amount);
            productLines = productLines == null ? List.of() : List.copyOf(productLines);
        }
    }

    public record ProductLine(
            int lineNo,
            long productId,
            long skuId,
            String productTitle,
            String skuCode,
            long quantity,
            BigDecimal payableAmount) {

        public ProductLine {
            payableAmount = payableAmount == null ? null : money(payableAmount);
        }
    }

    public record ProductContribution(
            long productId,
            String productTitle,
            long units,
            BigDecimal netRevenue,
            boolean revenueCovered) {

        public ProductContribution {
            netRevenue = money(netRevenue);
        }
    }

    public record DailySummary(
            LocalDate businessDate,
            long createdOrderCount,
            BigDecimal createdOrderAmount,
            long paymentCount,
            BigDecimal paymentAmount,
            long completedOrderCount,
            BigDecimal completedOrderAmount,
            long closedOrderCount,
            long afterSaleCount,
            BigDecimal afterSaleAmount,
            long refundCount,
            BigDecimal refundAmount,
            Instant updatedAt) {

        public DailySummary {
            createdOrderAmount = money(createdOrderAmount);
            paymentAmount = money(paymentAmount);
            completedOrderAmount = money(completedOrderAmount);
            afterSaleAmount = money(afterSaleAmount);
            refundAmount = money(refundAmount);
        }
    }

    public record ProductSummary(
            @JsonSerialize(using = ToStringSerializer.class) long productId,
            String productTitle,
            long completedOrderCount,
            long unitsSold,
            BigDecimal netRevenue,
            long revenueCoveredOrderCount) {

        public ProductSummary {
            netRevenue = money(netRevenue);
        }
    }

    public record OverviewTotals(
            long createdOrderCount,
            BigDecimal createdOrderAmount,
            long paymentCount,
            BigDecimal paymentAmount,
            long completedOrderCount,
            BigDecimal completedOrderAmount,
            long closedOrderCount,
            long afterSaleCount,
            BigDecimal afterSaleAmount,
            long refundCount,
            BigDecimal refundAmount,
            long uniqueCustomers) {

        public OverviewTotals {
            createdOrderAmount = money(createdOrderAmount);
            paymentAmount = money(paymentAmount);
            completedOrderAmount = money(completedOrderAmount);
            afterSaleAmount = money(afterSaleAmount);
            refundAmount = money(refundAmount);
        }
    }

    public record ProjectionFreshness(
            long sourceEventCount,
            Instant lastConsumedAt,
            Instant generatedAt) {
    }

    public record DashboardView(
            LocalDate from,
            LocalDate to,
            OverviewTotals totals,
            List<DailySummary> daily,
            List<ProductSummary> topProducts,
            ProjectionFreshness freshness) {

        public DashboardView {
            daily = List.copyOf(daily);
            topProducts = List.copyOf(topProducts);
        }
    }

    public record ProjectionIssue(
            String projection,
            String issueType,
            String key,
            String expected,
            String actual) {
    }

    public record ReconciliationView(
            LocalDate from,
            LocalDate to,
            int checkedDailyRows,
            int checkedProductRows,
            long issueCount,
            boolean saturated,
            List<ProjectionIssue> issues,
            Instant generatedAt) {

        public ReconciliationView {
            issues = List.copyOf(issues);
        }
    }

    public record RebuildView(
            String commandId,
            @JsonSerialize(using = ToStringSerializer.class) long operatorId,
            String reason,
            LocalDate from,
            LocalDate to,
            long sourceEventCount,
            long beforeIssueCount,
            long afterIssueCount,
            Instant createdAt) {
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
