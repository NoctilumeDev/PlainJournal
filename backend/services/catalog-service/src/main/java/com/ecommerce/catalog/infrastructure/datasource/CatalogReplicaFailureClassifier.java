package com.ecommerce.catalog.infrastructure.datasource;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

final class CatalogReplicaFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    private CatalogReplicaFailureClassifier() {
    }

    static boolean isConnectionFailure(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
                String message = sqlException.getMessage();
                if (sqlState == null && message != null
                        && message.toLowerCase(Locale.ROOT).contains("closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
