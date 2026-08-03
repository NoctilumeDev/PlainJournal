package com.ecommerce.trade.infrastructure.resilience;

import org.springframework.http.HttpStatusCode;

public final class MarketingPricingLockFailure extends RuntimeException {

    private final boolean retryable;
    private final boolean recordable;

    private MarketingPricingLockFailure(
            String message,
            boolean retryable,
            boolean recordable,
            Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.recordable = recordable;
    }

    public static MarketingPricingLockFailure forHttpStatus(HttpStatusCode status, Throwable cause) {
        boolean serverFailure = status.is5xxServerError();
        return new MarketingPricingLockFailure(
                "Marketing pricing lock returned HTTP " + status.value(),
                serverFailure,
                serverFailure,
                cause);
    }

    public static MarketingPricingLockFailure transientFailure(Throwable cause) {
        return new MarketingPricingLockFailure(
                "Marketing pricing lock transport failed", true, true, cause);
    }

    public static MarketingPricingLockFailure invalidResponse() {
        return invalidResponse(null);
    }

    public static MarketingPricingLockFailure invalidResponse(Throwable cause) {
        return new MarketingPricingLockFailure(
                "Marketing pricing lock returned an invalid response", false, true, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean recordable() {
        return recordable;
    }
}
