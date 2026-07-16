package com.ecommerce.identity.application.port;

import java.time.Instant;

public interface LoginAttemptStore {

    boolean isBlocked(String normalizedIdentifier, Instant now);

    FailureResult recordFailure(String normalizedIdentifier, Instant now);

    void clear(String normalizedIdentifier);

    record FailureResult(int failureCount, boolean blocked) {
    }
}
