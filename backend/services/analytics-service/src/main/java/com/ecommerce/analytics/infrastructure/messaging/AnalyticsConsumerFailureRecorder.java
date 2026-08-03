package com.ecommerce.analytics.infrastructure.messaging;

import com.ecommerce.analytics.infrastructure.persistence.AnalyticsRepository;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
public class AnalyticsConsumerFailureRecorder {

    private final AnalyticsRepository repository;
    private final int maximumAttempts;
    private final ConsumerFailureObservability observability;
    private final Duration retryDelay;

    public AnalyticsConsumerFailureRecorder(
            AnalyticsRepository repository,
            @Value("${ecommerce.messaging.consumer-failure.max-delivery-attempts:16}")
            int maximumAttempts,
            ConsumerFailureObservability observability,
            @Value("${ecommerce.messaging.consumer-failure-retry.retry-delay:PT15S}")
            Duration retryDelay) {
        this.repository = repository;
        this.maximumAttempts = Math.max(1, maximumAttempts);
        this.observability = observability;
        this.retryDelay = retryDelay;
    }

    public boolean record(MessageView message, String consumerGroup, Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        return record(message, consumerGroup, exception, attempts, attempts >= maximumAttempts);
    }

    public void recordTerminal(MessageView message, String consumerGroup, Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        record(message, consumerGroup, exception, attempts, true);
    }

    public void markRecovered(MessageView message, String consumerGroup) {
        if (repository.markConsumerRecovered(
                message.getMessageId().toString(),
                consumerGroup,
                repository.currentTime())) {
            observability.recovered();
        }
    }

    private boolean record(
            MessageView message,
            String consumerGroup,
            Exception exception,
            int attempts,
            boolean terminal) {
        Instant now = repository.currentTime();
        String status = terminal ? "NEEDS_ATTENTION" : "RETRYING";
        String error = conciseError(exception);
        String messageId = message.getMessageId().toString();
        Instant nextAttemptAt = terminal ? null : now.plus(retryDelay);
        boolean inserted = repository.insertConsumerFailureIfAbsent(
                messageId,
                consumerGroup,
                body(message.getBody()),
                attempts,
                status,
                error,
                now,
                nextAttemptAt);
        boolean updated = repository.markConsumerFailed(
                messageId,
                consumerGroup,
                attempts,
                status,
                error,
                now,
                nextAttemptAt);
        if (inserted || updated) {
            observability.failureRecorded(terminal);
        }
        return terminal;
    }

    private String body(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String conciseError(Exception exception) {
        String detail = exception.getMessage() == null ? "" : exception.getMessage();
        String message = exception.getClass().getSimpleName() + ": " + detail;
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
