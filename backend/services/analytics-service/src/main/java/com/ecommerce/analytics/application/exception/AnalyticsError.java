package com.ecommerce.analytics.application.exception;

public enum AnalyticsError {

    INVALID_DATE_RANGE("ANALYTICS_INVALID_DATE_RANGE", "The analytics date range is invalid"),
    IDEMPOTENCY_CONFLICT("ANALYTICS_IDEMPOTENCY_CONFLICT",
            "The analytics command id was already used for another request"),
    RECONCILIATION_SATURATED("ANALYTICS_RECONCILIATION_SATURATED",
            "The analytics range is too large for one bounded reconciliation pass"),
    REBUILD_DID_NOT_CONVERGE("ANALYTICS_REBUILD_DID_NOT_CONVERGE",
            "The analytics projection rebuild did not converge");

    private final String code;
    private final String message;

    AnalyticsError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
