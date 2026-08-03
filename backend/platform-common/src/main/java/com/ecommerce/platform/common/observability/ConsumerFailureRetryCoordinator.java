package com.ecommerce.platform.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConsumerFailureRetryCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(ConsumerFailureRetryCoordinator.class);

    private final String service;
    private final ConsumerFailureRetryStore store;
    private final ConsumerFailureObservability observability;
    private final int maximumAttempts;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration leaseDuration;
    private final String workerId;
    private final Map<String, ConsumerFailureRetryHandler> handlers;
    private final Set<String> missingHandlerWarnings = ConcurrentHashMap.newKeySet();

    public ConsumerFailureRetryCoordinator(
            String service,
            ConsumerFailureRetryStore store,
            ConsumerFailureObservability observability,
            int maximumAttempts,
            int batchSize,
            Duration retryDelay,
            Duration leaseDuration,
            String workerId,
            Collection<ConsumerFailureRetryHandler> handlers) {
        this.service = requireText(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.maximumAttempts = requirePositive(maximumAttempts, "maximumAttempts");
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.retryDelay = requirePositive(retryDelay, "retryDelay");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.workerId = workerId == null || workerId.isBlank()
                ? this.service + "-" + UUID.randomUUID()
                : workerId;
        this.handlers = indexHandlers(handlers);
    }

    @Scheduled(
            initialDelayString =
                    "${ecommerce.messaging.consumer-failure-retry.initial-delay:2000}",
            fixedDelayString =
                    "${ecommerce.messaging.consumer-failure-retry.fixed-delay:1000}",
            scheduler =
                    "${ecommerce.messaging.consumer-failure-retry.scheduler:taskScheduler}")
    public void retryDueFailures() {
        Instant selectedAt = store.currentTime();
        for (ConsumerFailureRetryEntry retry : store.selectRetryable(selectedAt, batchSize)) {
            retryOne(retry);
        }
    }

    private void retryOne(ConsumerFailureRetryEntry retry) {
        ConsumerFailureRetryHandler handler = handlers.get(retry.getConsumerGroup());
        if (handler == null) {
            if (missingHandlerWarnings.add(retry.getConsumerGroup())) {
                log.warn("Consumer failure retry is waiting for an enabled handler: "
                                + "service={}, consumerGroup={}",
                        service,
                        retry.getConsumerGroup());
            }
            return;
        }

        Instant claimedAt = store.currentTime();
        if (store.claimRetry(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                workerId,
                retry.getAttempts(),
                claimedAt,
                claimedAt.plus(leaseDuration)) != 1) {
            return;
        }

        try {
            handler.retry(retry.getRawPayload());
            int updated = store.markRetryRecovered(
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    workerId,
                    store.currentTime());
            if (updated > 0) {
                observability.recovered();
            } else {
                log.warn("Consumer failure recovered after its lease was lost: "
                                + "service={}, messageId={}, consumerGroup={}, owner={}",
                        service,
                        retry.getMessageId(),
                        retry.getConsumerGroup(),
                        workerId);
            }
        } catch (Exception exception) {
            recordRetryFailure(retry, handler, exception);
        }
    }

    private void recordRetryFailure(
            ConsumerFailureRetryEntry retry,
            ConsumerFailureRetryHandler handler,
            Exception exception) {
        int attempts = Math.max(1, retry.getAttempts()) + 1;
        boolean terminal = handler.isTerminal(exception) || attempts >= maximumAttempts;
        Instant now = store.currentTime();
        int updated = store.markRetryFailed(
                retry.getMessageId(),
                retry.getConsumerGroup(),
                workerId,
                attempts,
                terminal ? "NEEDS_ATTENTION" : "RETRYING",
                conciseError(exception),
                terminal ? null : now.plus(retryDelay),
                now);
        if (updated == 0) {
            log.warn("Consumer failure retry failed after its lease was lost: "
                            + "service={}, messageId={}, consumerGroup={}, owner={}",
                    service,
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    workerId,
                    exception);
            return;
        }
        observability.failureRecorded(terminal);
        if (terminal) {
            log.error("Consumer failure retry requires attention: "
                            + "service={}, messageId={}, consumerGroup={}, attempts={}",
                    service,
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    attempts,
                    exception);
        } else {
            log.warn("Consumer failure retry failed and was rescheduled: "
                            + "service={}, messageId={}, consumerGroup={}, attempts={}",
                    service,
                    retry.getMessageId(),
                    retry.getConsumerGroup(),
                    attempts,
                    exception);
        }
    }

    private Map<String, ConsumerFailureRetryHandler> indexHandlers(
            Collection<ConsumerFailureRetryHandler> values) {
        Objects.requireNonNull(values, "handlers");
        Map<String, ConsumerFailureRetryHandler> indexed = new HashMap<>();
        for (ConsumerFailureRetryHandler handler : values) {
            String consumerGroup = requireText(handler.consumerGroup(), "consumerGroup");
            ConsumerFailureRetryHandler previous = indexed.putIfAbsent(consumerGroup, handler);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate consumer failure retry handler for group " + consumerGroup);
            }
        }
        return Map.copyOf(indexed);
    }

    private String conciseError(Exception exception) {
        String detail = exception.getMessage() == null ? "" : exception.getMessage();
        String message = exception.getClass().getSimpleName() + ": " + detail;
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
