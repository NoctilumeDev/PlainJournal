package com.ecommerce.fulfillment.infrastructure.messaging;

import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
public class ConsumerFailureRecorder {
    private final ConsumerFailureMapper mapper;
    private final int maxDeliveryAttempts;
    private final ConsumerFailureObservability observability;
    private final Duration retryDelay;

    public ConsumerFailureRecorder(ConsumerFailureMapper mapper,
            @Value("${ecommerce.messaging.consumer-failure.max-delivery-attempts:16}") int maxDeliveryAttempts,
            ConsumerFailureObservability observability,
            @Value("${ecommerce.messaging.consumer-failure-retry.retry-delay:PT15S}")
            Duration retryDelay) {
        this.mapper = mapper;
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
        this.observability = observability;
        this.retryDelay = retryDelay;
    }

    public boolean record(MessageView message, String consumerGroup, Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        return record(message, consumerGroup, exception, attempts, attempts >= maxDeliveryAttempts);
    }

    public void recordTerminal(MessageView message, String consumerGroup, Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        record(message, consumerGroup, exception, attempts, true);
    }

    private boolean record(
            MessageView message,
            String consumerGroup,
            Exception exception,
            int attempts,
            boolean terminal) {
        String messageId = message.getMessageId().toString();
        String error = conciseError(exception);
        Instant now = mapper.currentTime();
        String status = terminal ? "NEEDS_ATTENTION" : "RETRYING";
        Instant nextAttemptAt = terminal ? null : now.plus(retryDelay);
        int inserted = mapper.insertIfAbsent(
                messageId, consumerGroup, readBody(message.getBody()), attempts,
                status, error, now, nextAttemptAt);
        int updated = mapper.markFailed(
                messageId, consumerGroup, attempts, status, error, now, nextAttemptAt);
        if (inserted > 0 || updated > 0) {
            observability.failureRecorded(terminal);
        }
        return terminal;
    }

    public void markRecovered(MessageView message, String consumerGroup) {
        if (mapper.markRecovered(
                message.getMessageId().toString(), consumerGroup, mapper.currentTime()) > 0) {
            observability.recovered();
        }
    }

    private String readBody(ByteBuffer source) {
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
