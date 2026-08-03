package com.ecommerce.notification.application.port;

public interface EmailSender {

    void send(EmailMessage message);

    record EmailMessage(
            String destination,
            String providerMessageId,
            String subject,
            String content) {
    }
}
