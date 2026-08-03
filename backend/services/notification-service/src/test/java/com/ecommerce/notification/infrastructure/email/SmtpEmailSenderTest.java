package com.ecommerce.notification.infrastructure.email;

import com.ecommerce.notification.application.port.EmailSender.EmailMessage;
import com.ecommerce.notification.infrastructure.config.NotificationDeliveryProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpEmailSenderTest {

    @Test
    void preservesStableMessageIdAndPlainTextBody() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, properties());
        ArgumentCaptor<MimeMessagePreparator> captor =
                ArgumentCaptor.forClass(MimeMessagePreparator.class);

        sender.send(new EmailMessage(
                "reader@example.com",
                "<plainjournal-notification-1@local>",
                "Payment successful",
                "Order ORDER-1 was paid successfully."));

        verify(mailSender).send(captor.capture());
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        captor.getValue().prepare(mimeMessage);
        assertThat(mimeMessage.getHeader("Message-ID", null))
                .isEqualTo("<plainjournal-notification-1@local>");
        assertThat(mimeMessage.getSubject()).isEqualTo("Payment successful");
        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("reader@example.com");
        assertThat(mimeMessage.getContent().toString())
                .contains("Order ORDER-1 was paid successfully.");
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(
                true,
                false,
                "no-reply@plainjournal.local",
                "notification-test",
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                10,
                0,
                250);
    }
}
