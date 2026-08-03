package com.ecommerce.notification.infrastructure.email;

import com.ecommerce.notification.application.model.NotificationModels.EmailDeliveryAttempt;
import com.ecommerce.notification.application.port.EmailSender;
import com.ecommerce.notification.application.port.EmailSender.EmailMessage;
import com.ecommerce.notification.application.service.NotificationDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.notification.email",
        name = "worker-enabled",
        havingValue = "true")
public class NotificationEmailDeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailDeliveryJob.class);

    private final NotificationDeliveryService deliveryService;
    private final EmailSender emailSender;

    public NotificationEmailDeliveryJob(
            NotificationDeliveryService deliveryService,
            EmailSender emailSender) {
        this.deliveryService = deliveryService;
        this.emailSender = emailSender;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.notification.email.initial-delay:2000}",
            fixedDelayString = "${ecommerce.notification.email.fixed-delay:1000}")
    public void deliver() {
        for (EmailDeliveryAttempt attempt : deliveryService.claimDue()) {
            try {
                emailSender.send(new EmailMessage(
                        attempt.destination(),
                        attempt.providerMessageId(),
                        attempt.subject(),
                        attempt.content()));
                if (!deliveryService.markSent(attempt.deliveryId(), attempt.attempt())) {
                    log.warn("Notification email was sent after its delivery lease was lost: "
                                    + "deliveryId={}, attempt={}",
                            attempt.deliveryId(),
                            attempt.attempt());
                }
            } catch (Exception exception) {
                boolean updated = deliveryService.markFailed(
                        attempt.deliveryId(),
                        attempt.attempt(),
                        exception);
                if (updated) {
                    log.warn("Notification email delivery failed: deliveryId={}, attempt={}",
                            attempt.deliveryId(), attempt.attempt(), exception);
                } else {
                    log.warn("Notification email failed after its delivery lease was lost: "
                                    + "deliveryId={}, attempt={}",
                            attempt.deliveryId(),
                            attempt.attempt(),
                            exception);
                }
            }
        }
    }
}
