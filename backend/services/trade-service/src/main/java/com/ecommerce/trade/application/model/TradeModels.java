package com.ecommerce.trade.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TradeModels {

    private TradeModels() {
    }

    public record CartItemView(
            @JsonSerialize(using = ToStringSerializer.class)
            Long id,
            @JsonSerialize(using = ToStringSerializer.class)
            Long productId,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            String productTitle,
            String skuName,
            String specJson,
            BigDecimal unitPrice,
            long quantity,
            boolean selected
    ) {
    }

    public record GuestBagItemCommand(Long productId, Long skuId, long quantity) {
    }

    public record OrderLineCommand(Long productId, Long skuId, long quantity) {
    }

    public record CreateOrderCommand(
            Long userId,
            String idempotencyKey,
            Long addressId,
            List<OrderLineCommand> items,
            List<String> benefitNos
    ) {
        public CreateOrderCommand {
            items = List.copyOf(items);
            benefitNos = benefitNos == null ? List.of() : benefitNos.stream().distinct().sorted().toList();
        }
    }

    public record AddressSnapshotView(
            @JsonSerialize(using = ToStringSerializer.class)
            Long sourceAddressId,
            String recipientName,
            String phone,
            String province,
            String provinceCode,
            String city,
            String cityCode,
            String district,
            String districtCode,
            String detailAddress,
            String postalCode
    ) {
    }

    public record OrderItemView(
            int lineNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long productId,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            String productTitle,
            String skuCode,
            String skuName,
            String specJson,
            String imageObjectKey,
            BigDecimal unitPrice,
            long quantity,
            BigDecimal lineAmount,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {
    }

    public record DiscountAllocationView(
            int lineNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            String benefitNo,
            String ruleCode,
            String benefitType,
            BigDecimal discountAmount
    ) {
    }

    public record PriceSnapshotView(
            String marketingLockNo,
            BigDecimal originalAmount,
            BigDecimal couponDiscount,
            BigDecimal redPacketDiscount,
            BigDecimal subsidyDiscount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            String pricingVersion,
            List<DiscountAllocationView> allocations
    ) {
        public PriceSnapshotView {
            allocations = List.copyOf(allocations);
        }
    }

    public record OrderView(
            String orderNo,
            String status,
            BigDecimal totalAmount,
            PriceSnapshotView priceSnapshot,
            Instant paymentDeadline,
            String closeReason,
            AddressSnapshotView deliveryAddress,
            List<OrderItemView> items,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
        public OrderView {
            items = List.copyOf(items);
        }
    }

    public record PaymentContextView(
            String orderNo,
            Long userId,
            String reservationNo,
            String paymentNo,
            String status,
            BigDecimal totalAmount,
            Instant paymentDeadline
    ) {
    }

    public record PaymentSucceededCommand(
            String eventId,
            String paymentNo,
            String orderNo,
            Long userId,
            String reservationNo,
            BigDecimal amount
    ) {
    }

    public record FlashSaleAdmissionAcceptedCommand(
            String eventId,
            String requestToken,
            String activityNo,
            Long userId,
            Long addressId,
            Long productId,
            Long skuId,
            BigDecimal salePrice,
            Instant acceptedAt,
            Instant activityEndsAt
    ) {
    }

    public record FulfillmentEventCommand(
            String eventId,
            String eventType,
            String fulfillmentNo,
            String orderNo,
            Long userId
    ) {
    }

    public record ApplyAfterSaleCommand(
            Long userId,
            String idempotencyKey,
            String orderNo,
            String reason
    ) {
    }

    public record ReviewAfterSaleCommand(
            String afterSaleNo,
            boolean approved,
            String reason,
            String operatorId
    ) {
    }

    public record AfterSaleItemView(
            int lineNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            String productTitle,
            String skuName,
            long quantity,
            BigDecimal lineAmount,
            BigDecimal discountAmount,
            BigDecimal refundableAmount
    ) {
    }

    public record AfterSaleView(
            String afterSaleNo,
            String orderNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long userId,
            String afterSaleType,
            String status,
            String reason,
            String reviewReason,
            BigDecimal refundAmount,
            String returnReceiptNo,
            String refundNo,
            List<AfterSaleItemView> items,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Instant approvedAt,
            Instant completedAt
    ) {
        public AfterSaleView {
            items = List.copyOf(items);
        }
    }

    public record AfterSaleFulfillmentEventCommand(
            String eventId,
            String eventType,
            String afterSaleNo,
            String returnReceiptNo,
            String orderNo,
            Long userId
    ) {
    }

    public record ReturnStockedCommand(
            String eventId,
            String afterSaleNo,
            String returnReceiptNo,
            String orderNo,
            Long userId,
            Long warehouseId
    ) {
    }

    public record RefundEventCommand(
            String eventId,
            String eventType,
            String refundNo,
            String afterSaleNo,
            String orderNo,
            String paymentNo,
            Long userId,
            BigDecimal amount
    ) {
    }
}
