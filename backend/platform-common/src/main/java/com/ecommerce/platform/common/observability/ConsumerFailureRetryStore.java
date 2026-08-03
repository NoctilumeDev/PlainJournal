package com.ecommerce.platform.common.observability;

import java.time.Instant;
import java.util.List;

public interface ConsumerFailureRetryStore extends ConsumerFailureStore {

    /**
     * Returns the database clock used to arbitrate retry leases across instances.
     *
     * <p>Lease ownership must not depend on an individual JVM wall clock because
     * hosts can be skewed even when they share the same database.</p>
     */
    Instant currentTime();

    List<ConsumerFailureRetryEntry> selectRetryable(Instant now, int limit);

    int claimRetry(
            String messageId,
            String consumerGroup,
            String owner,
            int expectedAttempts,
            Instant now,
            Instant claimUntil);

    int markRetryRecovered(
            String messageId,
            String consumerGroup,
            String owner,
            Instant now);

    int markRetryFailed(
            String messageId,
            String consumerGroup,
            String owner,
            int attempts,
            String status,
            String error,
            Instant nextAttemptAt,
            Instant now);
}
