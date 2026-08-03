package com.ecommerce.catalog.infrastructure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogReadReplicaProperties.class)
public class CatalogDataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties catalogPrimaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "catalogPrimaryDataSource")
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource catalogPrimaryDataSource(
            DataSourceProperties catalogPrimaryDataSourceProperties) {
        return catalogPrimaryDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("ecommerce.catalog.read-replica.datasource")
    public DataSourceProperties catalogReplicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "catalogReplicaDataSource")
    @ConditionalOnProperty(
            prefix = "ecommerce.catalog.read-replica",
            name = "enabled",
            havingValue = "true")
    @ConfigurationProperties("ecommerce.catalog.read-replica.datasource.hikari")
    public HikariDataSource catalogReplicaDataSource(
            @Qualifier("catalogReplicaDataSourceProperties")
            DataSourceProperties replicaProperties) {
        return replicaProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    CatalogDataSourceMetrics catalogDataSourceMetrics(MeterRegistry meterRegistry) {
        return new CatalogDataSourceMetrics(meterRegistry);
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("catalogPrimaryDataSource") DataSource primary,
            @Qualifier("catalogReplicaDataSource") ObjectProvider<DataSource> replicaProvider,
            CatalogReadReplicaProperties properties,
            CatalogDataSourceMetrics metrics) {
        return new CatalogRoutingDataSource(
                primary,
                replicaProvider.getIfAvailable(),
                properties,
                metrics);
    }

    @Bean
    CatalogReplicaReadAspect catalogReplicaReadAspect(
            CatalogReadReplicaProperties properties,
            CatalogDataSourceMetrics metrics) {
        return new CatalogReplicaReadAspect(properties, metrics);
    }

    @Bean
    CatalogReadConsistencyFilter catalogReadConsistencyFilter(
            CatalogDataSourceMetrics metrics) {
        return new CatalogReadConsistencyFilter(metrics);
    }
}
