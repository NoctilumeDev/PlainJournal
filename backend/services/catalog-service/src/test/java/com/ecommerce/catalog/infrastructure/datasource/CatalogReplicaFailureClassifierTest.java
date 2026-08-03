package com.ecommerce.catalog.infrastructure.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReplicaFailureClassifierTest {

    @Test
    void recognizesConnectionFailuresThroughNestedExceptions() {
        RuntimeException wrapped = new RuntimeException(
                "mapper failed",
                new CannotGetJdbcConnectionException(
                        "replica unavailable",
                        new SQLTransientConnectionException("connection refused", "08001")));

        assertThat(CatalogReplicaFailureClassifier.isConnectionFailure(wrapped)).isTrue();
    }

    @Test
    void doesNotReplaySqlOrBusinessFailuresOnPrimary() {
        assertThat(CatalogReplicaFailureClassifier.isConnectionFailure(
                new SQLException("bad SQL", "42000"))).isFalse();
        assertThat(CatalogReplicaFailureClassifier.isConnectionFailure(
                new IllegalStateException("business failure"))).isFalse();
    }
}
