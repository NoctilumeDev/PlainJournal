package com.ecommerce.inventory.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.List;

public final class InventoryModels {

    private InventoryModels() {
    }

    public record WarehouseView(
            @JsonSerialize(using = ToStringSerializer.class)
            Long id,
            String code,
            String name,
            String status,
            int version
    ) {
    }

    public record StockPosition(
            @JsonSerialize(using = ToStringSerializer.class)
            Long warehouseId,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            long onHand,
            long reserved,
            long available,
            int version
    ) {
    }

    public record StockSummary(
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            long onHand,
            long reserved,
            long available
    ) {
    }

    public record ReservationLineCommand(Long skuId, long quantity) {
    }

    public record ReserveInventoryCommand(
            String reservationNo,
            String orderNo,
            Long warehouseId,
            Instant expiresAt,
            List<ReservationLineCommand> items
    ) {
        public ReserveInventoryCommand {
            items = List.copyOf(items);
        }
    }

    public record ReservationItemView(
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            long quantity
    ) {
    }

    public record ReservationView(
            String reservationNo,
            String orderNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long warehouseId,
            String status,
            Instant expiresAt,
            int version,
            List<ReservationItemView> items
    ) {
        public ReservationView {
            items = List.copyOf(items);
        }
    }

    public record ReturnInspectedItem(
            int lineNo,
            Long skuId,
            long quantity
    ) {
    }

    public record ReturnInspectedCommand(
            String eventId,
            String returnReceiptNo,
            String afterSaleNo,
            String orderNo,
            Long userId,
            Long warehouseId,
            String reservationNo,
            List<ReturnInspectedItem> items
    ) {
        public ReturnInspectedCommand {
            items = List.copyOf(items);
        }
    }

    public record ReturnStockView(
            String afterSaleNo,
            String returnReceiptNo,
            String orderNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long userId,
            @JsonSerialize(using = ToStringSerializer.class)
            Long warehouseId,
            String reservationNo,
            String status,
            Instant createdAt,
            Instant stockedAt
    ) {
    }

    public record ReconciliationIssueView(
            String domain,
            String referenceNo,
            String issueType,
            String status,
            int occurrences,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            Instant resolvedAt
    ) {
    }
}
