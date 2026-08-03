package com.ecommerce.catalog.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ReviewModels {

    private ReviewModels() {
    }

    public record OrderCompletedEvent(
            String eventId,
            String orderNo,
            Long userId,
            Instant completedAt,
            List<OrderLineSnapshot> items) {

        public OrderCompletedEvent {
            items = List.copyOf(items);
        }
    }

    public record OrderLineSnapshot(
            int lineNo,
            Long productId,
            Long skuId,
            String productTitle,
            String skuCode,
            String skuName,
            String specJson,
            String imageObjectKey,
            long quantity) {
    }

    public record ReviewEligibilityView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String orderNo,
            int lineNo,
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            @JsonSerialize(using = ToStringSerializer.class) Long skuId,
            String productTitle,
            String skuCode,
            String skuName,
            String specJson,
            String imageObjectKey,
            long quantity,
            String status,
            @JsonSerialize(using = ToStringSerializer.class) Long reviewId,
            Instant completedAt) {
    }

    public record CreateReviewCommand(
            Long userId,
            Long eligibilityId,
            int rating,
            String content,
            boolean anonymous,
            String idempotencyKey) {
    }

    public record ReviewReplyView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String content,
            Instant createdAt) {
    }

    public record ProductReviewView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            @JsonSerialize(using = ToStringSerializer.class) Long skuId,
            String skuName,
            String specJson,
            int rating,
            String content,
            boolean anonymous,
            String authorLabel,
            String status,
            long likeCount,
            boolean likedByViewer,
            ReviewReplyView reply,
            Instant createdAt) {
    }

    public record ReviewSummaryView(
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            long reviewCount,
            BigDecimal averageRating,
            long rating1Count,
            long rating2Count,
            long rating3Count,
            long rating4Count,
            long rating5Count) {
    }

    public record ReviewReportView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long reviewId,
            @JsonSerialize(using = ToStringSerializer.class) Long productId,
            int rating,
            String reviewContent,
            String reasonCode,
            String detail,
            String status,
            String resolution,
            Instant createdAt,
            Instant resolvedAt) {
    }

    public record ReviewReportReceipt(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long reviewId,
            String status,
            Instant createdAt) {
    }

    public record ModerationResultView(
            @JsonSerialize(using = ToStringSerializer.class) Long reportId,
            @JsonSerialize(using = ToStringSerializer.class) Long reviewId,
            String commandId,
            String resolution,
            String reviewStatusBefore,
            String reviewStatusAfter,
            Instant resolvedAt) {
    }
}
