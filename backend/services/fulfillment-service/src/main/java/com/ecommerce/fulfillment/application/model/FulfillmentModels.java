package com.ecommerce.fulfillment.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FulfillmentModels {

    private FulfillmentModels() {
    }

    public record DeliveryAddress(
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

    public record OrderPaidCommand(
            String eventId,
            String orderNo,
            Long userId,
            DeliveryAddress deliveryAddress
    ) {
    }

    public record ShipCommand(String carrier, String trackingNo, String operatorId) {
    }

    public record AddTraceCommand(
            String externalEventId,
            String nodeType,
            String description,
            String locationName,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant occurredAt,
            String operatorId
    ) {
    }

    public record LogisticsTraceView(
            String externalEventId,
            String nodeType,
            String description,
            String locationName,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant occurredAt
    ) {
    }

    public record FulfillmentView(
            String fulfillmentNo,
            String orderNo,
            Long userId,
            DeliveryAddress deliveryAddress,
            String status,
            String carrier,
            String trackingNo,
            List<LogisticsTraceView> traces,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Instant pickedAt,
            Instant packedAt,
            Instant shippedAt,
            Instant signedAt
    ) {
        public FulfillmentView {
            traces = List.copyOf(traces);
        }
    }

    public record AfterSaleApprovedItem(
            int lineNo,
            Long skuId,
            long quantity,
            BigDecimal refundableAmount
    ) {
    }

    public record AfterSaleApprovedCommand(
            String eventId,
            String afterSaleNo,
            String orderNo,
            Long userId,
            Long warehouseId,
            String reservationNo,
            BigDecimal refundAmount,
            List<AfterSaleApprovedItem> items
    ) {
        public AfterSaleApprovedCommand {
            items = List.copyOf(items);
        }
    }

    public record SubmitReturnShipmentCommand(
            String carrier,
            String trackingNo
    ) {
    }

    public record ReturnItemView(
            int lineNo,
            Long skuId,
            long quantity,
            BigDecimal refundableAmount
    ) {
    }

    public record ReturnReceiptView(
            String returnReceiptNo,
            String afterSaleNo,
            String orderNo,
            Long userId,
            Long warehouseId,
            String reservationNo,
            String status,
            BigDecimal refundAmount,
            String carrier,
            String trackingNo,
            String inspectionRemark,
            List<ReturnItemView> items,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Instant shippedAt,
            Instant receivedAt,
            Instant inspectedAt
    ) {
        public ReturnReceiptView {
            items = List.copyOf(items);
        }
    }
}
