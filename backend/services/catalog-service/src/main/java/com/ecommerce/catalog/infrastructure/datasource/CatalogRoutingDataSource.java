package com.ecommerce.catalog.infrastructure.datasource;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

final class CatalogRoutingDataSource extends AbstractDataSource {

    private final DataSource primary;
    private final DataSource replica;
    private final CatalogReadReplicaProperties properties;
    private final CatalogDataSourceMetrics metrics;

    CatalogRoutingDataSource(
            DataSource primary,
            DataSource replica,
            CatalogReadReplicaProperties properties,
            CatalogDataSourceMetrics metrics) {
        this.primary = primary;
        this.replica = replica;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSource selected = selectedDataSource();
        try {
            return selected.getConnection();
        } catch (SQLException exception) {
            recordFailure(selected);
            throw exception;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        DataSource selected = selectedDataSource();
        try {
            return selected.getConnection(username, password);
        } catch (SQLException exception) {
            recordFailure(selected);
            throw exception;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        return primary.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || primary.isWrapperFor(iface);
    }

    private DataSource selectedDataSource() {
        boolean useReplica = properties.isEnabled()
                && replica != null
                && CatalogReadRouteContext.shouldUseReplica();
        metrics.recordConnectionAttempt(useReplica);
        return useReplica ? replica : primary;
    }

    private void recordFailure(DataSource selected) {
        if (selected == replica) {
            metrics.recordReplicaConnectionFailure();
        }
    }
}
