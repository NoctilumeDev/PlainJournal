package com.ecommerce.marketing.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

public final class FlashSaleModels {

    private FlashSaleModels() {
    }

    public record CreateFlashSaleCommand(
            String name,
            Long productId,
            Long skuId,
            BigDecimal salePrice,
            int admissionLimit,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    public record FlashSaleActivityView(
            String activityNo,
            String name,
            @JsonSerialize(using = ToStringSerializer.class)
            Long productId,
            @JsonSerialize(using = ToStringSerializer.class)
            Long skuId,
            BigDecimal salePrice,
            int admissionLimit,
            String status,
            Instant startsAt,
            Instant endsAt,
            int version
    ) {
    }

    public record FlashSaleAdmissionView(
            String requestToken,
            String activityNo,
            @JsonSerialize(using = ToStringSerializer.class)
            Long userId,
            String status,
            Integer remainingAdmissions,
            Instant acceptedAt,
            String orderNo,
            String failureCode,
            Instant completedAt
    ) {
    }

    public record FlashSaleOrderResultCommand(
            String eventId,
            String eventType,
            String requestToken,
            String activityNo,
            Long userId,
            String orderNo,
            String failureCode,
            Instant completedAt
    ) {
    }
}
