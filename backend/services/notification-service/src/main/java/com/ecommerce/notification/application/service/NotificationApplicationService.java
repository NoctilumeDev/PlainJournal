package com.ecommerce.notification.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.notification.application.exception.NotificationError;
import com.ecommerce.notification.application.exception.NotificationException;
import com.ecommerce.notification.application.model.NotificationModels.DeliveryRetryView;
import com.ecommerce.notification.application.model.NotificationModels.DomainEvent;
import com.ecommerce.notification.application.model.NotificationModels.EmailPreferenceView;
import com.ecommerce.notification.application.model.NotificationModels.NotificationView;
import com.ecommerce.notification.infrastructure.config.NotificationDeliveryProperties;
import com.ecommerce.notification.infrastructure.persistence.NotificationRepository;
import com.ecommerce.notification.infrastructure.persistence.NotificationRepository.DeliveryRetryAudit;
import com.ecommerce.notification.infrastructure.persistence.NotificationRepository.DeliveryState;
import com.ecommerce.platform.common.api.CursorPageResponse;
import com.ecommerce.platform.common.api.KeysetCursor;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class NotificationApplicationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository repository;
    private final NotificationDeliveryProperties deliveryProperties;

    public NotificationApplicationService(
            NotificationRepository repository,
            NotificationDeliveryProperties deliveryProperties) {
        this.repository = repository;
        this.deliveryProperties = deliveryProperties;
    }

    @Transactional
    public boolean acceptDomainEvent(DomainEvent event, String consumerGroup) {
        Instant now = repository.currentTime();
        String payloadFingerprint = PayloadFingerprint.of(
                event.eventType(), event.userId(), event.payload());
        if (!repository.insertConsumed(
                event.eventId(), consumerGroup, payloadFingerprint, now)) {
            String storedFingerprint = repository.findConsumedFingerprint(
                    event.eventId(), consumerGroup);
            if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                throw new NotificationException(NotificationError.IDEMPOTENCY_CONFLICT);
            }
            return false;
        }
        RenderedNotification rendered = render(event);
        long taskId = IdWorker.getId();
        repository.insertTask(
                taskId,
                event.eventId(),
                event.eventType(),
                rendered.templateCode(),
                event.userId(),
                rendered.referenceType(),
                rendered.referenceNo(),
                rendered.title(),
                rendered.content(),
                now);
        repository.insertInApp(IdWorker.getId(), taskId, event.userId(), now);

        EmailPreferenceView preference = repository.findPreference(event.userId());
        if (deliveryProperties.enabled()
                && preference != null
                && preference.enabled()
                && preference.email() != null
                && !preference.email().isBlank()) {
            long deliveryId = IdWorker.getId();
            repository.insertEmailDelivery(
                    deliveryId,
                    taskId,
                    preference.email(),
                    "<plainjournal-notification-" + deliveryId + "@local>",
                    now);
        }
        return true;
    }

    @Transactional
    public EmailPreferenceView saveEmailPreference(long userId, String email, boolean enabled) {
        String normalized = email == null || email.isBlank()
                ? null
                : email.strip().toLowerCase(Locale.ROOT);
        return repository.savePreference(userId, normalized, enabled, repository.currentTime());
    }

    public CursorPageResponse<NotificationView> list(
            long userId,
            String encodedCursor,
            Integer requestedSize) {
        int size = requestedSize == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(MAX_PAGE_SIZE, requestedSize));
        KeysetCursor cursor = decodeCursor(encodedCursor);
        List<NotificationView> rows = repository.listNotifications(
                userId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                size + 1);
        boolean hasMore = rows.size() > size;
        List<NotificationView> items = hasMore ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows);
        String nextCursor = null;
        if (hasMore) {
            NotificationView last = items.get(items.size() - 1);
            nextCursor = new KeysetCursor(last.createdAt(), last.id()).encode();
        }
        return new CursorPageResponse<>(items, nextCursor, hasMore);
    }

    public long unreadCount(long userId) {
        return repository.countUnread(userId);
    }

    @Transactional
    public void markRead(long userId, long notificationId) {
        if (!repository.notificationExists(notificationId, userId)) {
            throw new NotificationException(NotificationError.NOTIFICATION_NOT_FOUND);
        }
        repository.markRead(notificationId, userId, repository.currentTime());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryRetryView retryEmailDelivery(
            long operatorId,
            long deliveryId,
            String commandId,
            String reason) {
        // The delivery row owns retry serialization. Audit replay is checked only after
        // a waiting transaction has acquired that lock and can see the winner's commit.
        DeliveryState delivery = repository.findDeliveryForUpdate(deliveryId);
        if (delivery == null) {
            throw new NotificationException(NotificationError.DELIVERY_NOT_FOUND);
        }
        DeliveryRetryAudit existing = repository.findRetryAudit(commandId);
        if (existing != null) {
            return replayRetry(existing, operatorId, deliveryId, reason);
        }
        if (!"NEEDS_ATTENTION".equals(delivery.status())) {
            throw new NotificationException(NotificationError.DELIVERY_RETRY_NOT_ALLOWED);
        }
        Instant now = repository.currentTime();
        if (!repository.insertRetryAudit(
                IdWorker.getId(),
                commandId,
                deliveryId,
                operatorId,
                reason,
                delivery.status(),
                "RETRY",
                now)) {
            DeliveryRetryAudit raced = repository.findRetryAudit(commandId);
            if (raced != null) {
                return replayRetry(raced, operatorId, deliveryId, reason);
            }
            throw new NotificationException(NotificationError.DELIVERY_RETRY_NOT_ALLOWED);
        }
        repository.resetDeliveryForRetry(deliveryId, now);
        return repository.findRetryAudit(commandId).view();
    }

    private DeliveryRetryView replayRetry(
            DeliveryRetryAudit existing,
            long operatorId,
            long deliveryId,
            String reason) {
        if (existing.view().deliveryId() != deliveryId
                || existing.operatorId() != operatorId
                || !Objects.equals(existing.reason(), reason)) {
            throw new NotificationException(NotificationError.IDEMPOTENCY_CONFLICT);
        }
        return existing.view();
    }

    private KeysetCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            return KeysetCursor.decode(encodedCursor);
        } catch (IllegalArgumentException exception) {
            throw new NotificationException(NotificationError.INVALID_CURSOR);
        }
    }

    private RenderedNotification render(DomainEvent event) {
        JsonNode payload = event.payload();
        return switch (event.eventType()) {
            case "PaymentSucceeded" -> new RenderedNotification(
                    "PAYMENT_SUCCEEDED",
                    "ORDER",
                    requiredText(payload, "orderNo"),
                    "Payment successful",
                    "Order " + requiredText(payload, "orderNo") + " was paid successfully.");
            case "RefundSucceeded" -> new RenderedNotification(
                    "REFUND_SUCCEEDED",
                    "REFUND",
                    requiredText(payload, "refundNo"),
                    "Refund completed",
                    "Refund " + requiredText(payload, "refundNo")
                            + " for order " + requiredText(payload, "orderNo")
                            + " was completed.");
            case "ShipmentDispatched" -> new RenderedNotification(
                    "SHIPMENT_DISPATCHED",
                    "ORDER",
                    requiredText(payload, "orderNo"),
                    "Order shipped",
                    "Order " + requiredText(payload, "orderNo")
                            + " was shipped via " + requiredText(payload, "carrier")
                            + ", tracking number " + requiredText(payload, "trackingNo") + ".");
            case "ShipmentSigned" -> new RenderedNotification(
                    "SHIPMENT_SIGNED",
                    "ORDER",
                    requiredText(payload, "orderNo"),
                    "Order delivered",
                    "Order " + requiredText(payload, "orderNo") + " was marked as delivered.");
            default -> throw new IllegalArgumentException(
                    "Unsupported notification event type: " + event.eventType());
        };
    }

    private String requiredText(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing notification event field: " + field);
        }
        return value;
    }

    private record RenderedNotification(
            String templateCode,
            String referenceType,
            String referenceNo,
            String title,
            String content) {
    }
}
