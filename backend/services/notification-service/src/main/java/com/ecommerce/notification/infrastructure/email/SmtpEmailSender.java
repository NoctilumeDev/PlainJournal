package com.ecommerce.notification.infrastructure.email;

import com.ecommerce.notification.application.port.EmailSender;
import com.ecommerce.notification.infrastructure.config.NotificationDeliveryProperties;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final NotificationDeliveryProperties properties;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            NotificationDeliveryProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(EmailMessage message) {
        mailSender.send((MimeMessage mimeMessage) -> {
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    false,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(message.destination());
            helper.setSubject(message.subject());
            helper.setText(message.content(), false);
            mimeMessage.setHeader("Message-ID", message.providerMessageId());
            mimeMessage.setHeader("X-PlainJournal-Delivery-Id", message.providerMessageId());
        });
    }
}
