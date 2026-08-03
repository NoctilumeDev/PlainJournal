package com.ecommerce.payment.infrastructure.resilience;

import org.springframework.http.HttpStatusCode;

public final class RemoteDependencyFailure extends RuntimeException {

    private final boolean retryable;
    private final boolean recordable;

    private RemoteDependencyFailure(String message, boolean retryable, boolean recordable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.recordable = recordable;
    }

    public static RemoteDependencyFailure forHttpStatus(HttpStatusCode status, Throwable cause) {
        boolean serverFailure = status.is5xxServerError();
        return new RemoteDependencyFailure(
                "Remote dependency returned HTTP " + status.value(),
                serverFailure,
                serverFailure,
                cause);
    }

    public static RemoteDependencyFailure transientFailure(Throwable cause) {
        return new RemoteDependencyFailure("Remote dependency transport failed", true, true, cause);
    }

    public static RemoteDependencyFailure invalidResponse() {
        return new RemoteDependencyFailure("Remote dependency returned an invalid response", false, true, null);
    }

    public static RemoteDependencyFailure invalidResponse(Throwable cause) {
        return new RemoteDependencyFailure("Remote dependency returned an invalid response", false, true, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean recordable() {
        return recordable;
    }
}
