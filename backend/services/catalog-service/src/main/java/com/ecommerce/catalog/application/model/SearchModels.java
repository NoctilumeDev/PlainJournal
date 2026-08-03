package com.ecommerce.catalog.application.model;

import com.ecommerce.catalog.application.model.CatalogModels.ProductSummary;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.List;

public final class SearchModels {

    private SearchModels() {
    }

    public record ProductSearchPage(
            List<ProductSummary> items,
            long page,
            long size,
            long matchedTotal,
            String source,
            boolean degraded
    ) {
        public ProductSearchPage {
            items = List.copyOf(items);
        }
    }

    public record SearchOutboxView(
            String id,
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            long targetRevision,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record SearchRecoveryView(
            String commandId,
            String outboxId,
            String statusBefore,
            String statusAfter,
            Instant recoveredAt
    ) {
    }

    public record SearchRebuildView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String commandId,
            String status,
            String targetIndex,
            int attempts,
            long indexedCount,
            String lastError,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record SearchRebuildRecoveryView(
            String commandId,
            @JsonSerialize(using = ToStringSerializer.class) Long rebuildId,
            String statusBefore,
            String statusAfter,
            Instant recoveredAt
    ) {
    }

    public record SearchReconciliationIssueView(
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            String issueType,
            String status,
            Long mysqlRevision,
            Long indexRevision,
            int occurrences,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            Instant resolvedAt
    ) {
    }

    public record SearchReconciliationResult(
            int mysqlDocuments,
            int indexDocuments,
            int missing,
            int stale,
            int orphan,
            int opened,
            int resolved,
            int repairEvents,
            boolean saturated
    ) {
    }
}
