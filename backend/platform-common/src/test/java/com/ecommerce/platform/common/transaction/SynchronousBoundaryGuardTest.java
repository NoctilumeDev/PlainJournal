package com.ecommerce.platform.common.transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynchronousBoundaryGuardTest {

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void permitsCallsWhenNoOwnerTransactionIsActive() {
        assertThatCode(() -> SynchronousBoundaryGuard.requireOutsideTransaction("test"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCallsBeforeNetworkIoWhenOwnerTransactionIsActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> SynchronousBoundaryGuard.requireOutsideTransaction("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a local transaction")
                .hasMessageContaining("test");
    }
}
