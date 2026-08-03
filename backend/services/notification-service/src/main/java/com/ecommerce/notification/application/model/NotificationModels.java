package com.ecommerce.notification.application.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public final class NotificationModels {

    private NotificationModels() {
    }

    public record DomainEvent(
            String eventId,
            String eventType,
            long userId,
            JsonNode payload) {
    }

    public record NotificationView(
            @JsonSerialize(using = ToStringSerializer.class) long id,
            String templateCode,
            String referenceType,
            String referenceNo,
            String title,
            String content,
            String status,
            Instant readAt,
            Instant createdAt) {
    }

    public record EmailPreferenceView(
            @JsonSerialize(using = ToStringSerializer.class) long userId,
            String email,
            boolean enabled,
            Instant updatedAt) {
    }

    public record EmailDeliveryAttempt(
            @JsonSerialize(using = ToStringSerializer.class) long deliveryId,
            int attempt,
            String destination,
            String providerMessageId,
            String subject,
            String content) {
    }

    public record DeliveryRetryView(
            @JsonSerialize(using = ToStringSerializer.class) long deliveryId,
            String commandId,
            String beforeStatus,
            String afterStatus,
            Instant acceptedAt) {
    }
}
