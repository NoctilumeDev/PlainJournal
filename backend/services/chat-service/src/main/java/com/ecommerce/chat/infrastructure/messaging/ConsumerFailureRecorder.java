package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ConsumerFailureRetryEntity;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class ConsumerFailureRecorder {

    private final ConsumerFailureMapper mapper;
    private final int maxDeliveryAttempts;
    private final ConsumerFailureObservability observability;
    private final ChatConsumerFailureRetryProperties retryProperties;

    public ConsumerFailureRecorder(
            ConsumerFailureMapper mapper,
            @Value("${ecommerce.messaging.consumer-failure.max-delivery-attempts:16}")
            int maxDeliveryAttempts,
            ConsumerFailureObservability observability,
            ChatConsumerFailureRetryProperties retryProperties) {
        this.mapper = mapper;
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
        this.observability = observability;
        this.retryProperties = retryProperties;
    }

    @Transactional
    public boolean record(MessageView message, String consumerGroup, Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        return record(
                message,
                consumerGroup,
                exception,
                attempts,
                attempts >= maxDeliveryAttempts);
    }

    @Transactional
    public void recordTerminal(
            MessageView message,
            String consumerGroup,
            Exception exception) {
        int attempts = Math.max(1, message.getDeliveryAttempt());
        record(message, consumerGroup, exception, attempts, true);
    }

    public void markRecovered(MessageView message, String consumerGroup) {
        int updated = mapper.markRecovered(
                message.getMessageId().toString(),
                consumerGroup,
                mapper.currentTime());
        if (updated > 0) {
            observability.recovered();
        }
    }

    public boolean markRetryRecovered(
            ConsumerFailureRetryEntity retry,
            String owner) {
        int updated = mapper.markRetryRecovered(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                owner,
                mapper.currentTime());
        if (updated > 0) {
            observability.recovered();
            return true;
        }
        return false;
    }

    public RetryFailureResult recordRetryFailure(
            ConsumerFailureRetryEntity retry,
            String owner,
            Exception exception,
            boolean immediatelyTerminal) {
        int attempts = Math.max(1, retry.getAttempts()) + 1;
        boolean terminal = immediatelyTerminal || attempts >= maxDeliveryAttempts;
        Instant now = mapper.currentTime();
        int updated = mapper.markRetryFailed(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                owner,
                attempts,
                terminal ? "NEEDS_ATTENTION" : "RETRYING",
                conciseError(exception),
                terminal ? null : now.plus(retryProperties.retryDelay()),
                now);
        if (updated > 0) {
            observability.failureRecorded(terminal);
        }
        return new RetryFailureResult(updated > 0, terminal, attempts);
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
        Instant nextAttemptAt = terminal ? null : now.plus(retryProperties.retryDelay());
        int inserted = mapper.insertIfAbsent(
                messageId,
                consumerGroup,
                readBody(message.getBody()),
                attempts,
                status,
                error,
                now,
                nextAttemptAt);
        int updated = mapper.markFailed(
                messageId,
                consumerGroup,
                attempts,
                status,
                error,
                now,
                nextAttemptAt);
        if (inserted > 0 || updated > 0) {
            observability.failureRecorded(terminal);
        }
        return terminal;
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

    public record RetryFailureResult(
            boolean updated,
            boolean terminal,
            int attempts) {
    }
}
