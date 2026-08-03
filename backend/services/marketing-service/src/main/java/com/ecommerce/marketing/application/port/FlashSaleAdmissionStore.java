package com.ecommerce.marketing.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface FlashSaleAdmissionStore {

    void preheat(Activity activity, Instant now);

    Decision admit(
            String activityNo,
            Long userId,
            String requestKey,
            String candidateToken,
            Instant now);

    Optional<Snapshot> find(String requestToken);

    record Activity(
            String activityNo,
            int admissionLimit,
            int admittedCount,
            Instant startsAt,
            Instant endsAt,
            Duration resultRetention
    ) {
    }

    record Decision(
            Outcome outcome,
            String requestToken,
            int remainingAdmissions,
            Instant acceptedAt
    ) {
    }

    record Snapshot(
            String requestToken,
            String activityNo,
            Long userId,
            String status,
            int remainingAdmissions,
            Instant acceptedAt
    ) {
    }

    enum Outcome {
        ACCEPTED,
        REPLAYED,
        NOT_READY,
        NOT_ACTIVE,
        NOT_STARTED,
        ENDED,
        SOLD_OUT
    }
}
