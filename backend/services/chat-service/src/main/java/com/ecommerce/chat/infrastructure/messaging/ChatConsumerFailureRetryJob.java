package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.infrastructure.persistence.entity.ConsumerFailureRetryEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatConsumerFailureRetryJob {

    private static final Logger log =
            LoggerFactory.getLogger(ChatConsumerFailureRetryJob.class);

    private final ConsumerFailureMapper failureMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ChatStoredEventConsumer storedEventConsumer;
    private final ChatDeliveryEventConsumer deliveryEventConsumer;
    private final ChatRealtimeProperties realtimeProperties;
    private final ChatConsumerFailureRetryProperties retryProperties;

    public ChatConsumerFailureRetryJob(
            ConsumerFailureMapper failureMapper,
            ConsumerFailureRecorder failureRecorder,
            ChatStoredEventConsumer storedEventConsumer,
            ChatDeliveryEventConsumer deliveryEventConsumer,
            ChatRealtimeProperties realtimeProperties,
            ChatConsumerFailureRetryProperties retryProperties) {
        this.failureMapper = failureMapper;
        this.failureRecorder = failureRecorder;
        this.storedEventConsumer = storedEventConsumer;
        this.deliveryEventConsumer = deliveryEventConsumer;
        this.realtimeProperties = realtimeProperties;
        this.retryProperties = retryProperties;
    }

    @Scheduled(
            initialDelayString =
                    "${ecommerce.chat.consumer-failure-retry.initial-delay:2000}",
            fixedDelayString =
                    "${ecommerce.chat.consumer-failure-retry.fixed-delay:1000}")
    public void retryDueFailures() {
        Instant selectedAt = failureMapper.currentTime();
        for (ConsumerFailureRetryEntity retry : failureMapper.selectRetryable(
                realtimeProperties.dispatcherConsumerGroup(),
                realtimeProperties.deliveryConsumerGroup(),
                selectedAt,
                retryProperties.batchSize())) {
            claimAndRetry(retry);
        }
    }

    private void claimAndRetry(ConsumerFailureRetryEntity retry) {
        Instant claimedAt = failureMapper.currentTime();
        if (failureMapper.claimRetry(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                retryProperties.workerId(),
                retry.getAttempts(),
                claimedAt,
                claimedAt.plus(retryProperties.leaseDuration())) != 1) {
            return;
        }
        try {
            retryPayload(retry);
            markRecovered(retry);
        } catch (IOException exception) {
            markRecovered(retry);
            log.info("Chat delivery retry target is offline; durable offline replay remains "
                            + "authoritative: messageId={}, consumerGroup={}",
                    retry.getMessageId(),
                    retry.getConsumerGroup());
        } catch (IllegalArgumentException exception) {
            recordFailure(retry, exception, true);
        } catch (Exception exception) {
            recordFailure(retry, exception, false);
        }
    }

    private void retryPayload(ConsumerFailureRetryEntity retry) throws IOException {
        if (realtimeProperties.dispatcherConsumerGroup().equals(retry.getConsumerGroup())) {
            storedEventConsumer.retryPayload(retry.getRawPayload());
            return;
        }
        if (realtimeProperties.deliveryConsumerGroup().equals(retry.getConsumerGroup())) {
            deliveryEventConsumer.retryPayload(retry.getRawPayload());
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported Chat consumer failure group: " + retry.getConsumerGroup());
    }

    private void markRecovered(ConsumerFailureRetryEntity retry) {
        if (!failureRecorder.markRetryRecovered(retry, retryProperties.workerId())) {
            log.warn("Chat consumer failure recovered after its retry lease was lost: "
                            + "messageId={}, consumerGroup={}, owner={}",
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    retryProperties.workerId());
        }
    }

    private void recordFailure(
            ConsumerFailureRetryEntity retry,
            Exception exception,
            boolean immediatelyTerminal) {
        ConsumerFailureRecorder.RetryFailureResult result =
                failureRecorder.recordRetryFailure(
                        retry,
                        retryProperties.workerId(),
                        exception,
                        immediatelyTerminal);
        if (!result.updated()) {
            log.warn("Chat consumer retry failed after its lease was lost: "
                            + "messageId={}, consumerGroup={}, owner={}",
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    retryProperties.workerId(),
                    exception);
            return;
        }
        if (result.terminal()) {
            log.error("Chat consumer retry requires attention: messageId={}, "
                            + "consumerGroup={}, attempts={}",
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    result.attempts(),
                    exception);
        } else {
            log.warn("Chat consumer retry failed and was rescheduled: messageId={}, "
                            + "consumerGroup={}, attempts={}",
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    result.attempts(),
                    exception);
        }
    }
}
