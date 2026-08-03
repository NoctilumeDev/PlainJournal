package com.ecommerce.platform.common.transaction;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Prevents a synchronous network dependency from accidentally running while
 * database locks and an owner-domain transaction are active.
 */
public final class SynchronousBoundaryGuard {

    private SynchronousBoundaryGuard() {
    }

    public static void requireOutsideTransaction(String boundary) {
        Objects.requireNonNull(boundary, "boundary");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Synchronous boundary must execute outside a local transaction: " + boundary);
        }
    }
}
