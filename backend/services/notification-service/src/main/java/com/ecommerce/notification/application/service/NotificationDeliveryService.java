package com.ecommerce.notification.application.service;

import com.ecommerce.notification.application.model.NotificationModels.EmailDeliveryAttempt;
import com.ecommerce.notification.infrastructure.config.NotificationDeliveryProperties;
import com.ecommerce.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationDeliveryService {

    private final NotificationRepository repository;
    private final NotificationDeliveryProperties properties;

    public NotificationDeliveryService(
            NotificationRepository repository,
            NotificationDeliveryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public List<EmailDeliveryAttempt> claimDue() {
        Instant now = repository.currentTime();
        List<EmailDeliveryAttempt> due = repository.selectDueEmailDeliveriesForUpdate(
                now,
                properties.batchSize());
        List<EmailDeliveryAttempt> claimed = new ArrayList<>();
        for (EmailDeliveryAttempt candidate : due) {
            if (repository.claimEmailDelivery(
                    candidate.deliveryId(),
                    candidate.attempt(),
                    properties.workerId(),
                    now.plus(properties.leaseDuration()),
                    now)) {
                claimed.add(new EmailDeliveryAttempt(
                        candidate.deliveryId(),
                        candidate.attempt() + 1,
                        candidate.destination(),
                        candidate.providerMessageId(),
                        candidate.subject(),
                        candidate.content()));
            }
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public boolean markSent(long deliveryId, int attempt) {
        return repository.markDeliverySent(
                deliveryId,
                properties.workerId(),
                attempt,
                repository.currentTime());
    }

    @Transactional
    public boolean markFailed(long deliveryId, int attempt, Exception exception) {
        Instant now = repository.currentTime();
        boolean terminal = attempt >= properties.maximumAttempts();
        Instant nextAttemptAt = terminal
                ? now
                : now.plus(properties.retryDelay().multipliedBy(Math.max(1, attempt)));
        return repository.markDeliveryFailed(
                deliveryId,
                properties.workerId(),
                attempt,
                terminal ? "NEEDS_ATTENTION" : "RETRY",
                nextAttemptAt,
                conciseError(exception),
                now);
    }

    private String conciseError(Exception exception) {
        String detail = exception.getMessage() == null ? "" : exception.getMessage();
        String message = exception.getClass().getSimpleName() + ": " + detail;
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
