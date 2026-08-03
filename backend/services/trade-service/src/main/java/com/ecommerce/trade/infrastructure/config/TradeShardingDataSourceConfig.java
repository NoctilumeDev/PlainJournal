package com.ecommerce.trade.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepositoryConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.HintShardingStrategyConfiguration;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Configuration
@ConditionalOnProperty(prefix = "ecommerce.trade.sharding", name = "enabled", havingValue = "true")
public class TradeShardingDataSourceConfig {

    private static final Logger log =
            LoggerFactory.getLogger(TradeShardingDataSourceConfig.class);

    private static final List<String> SHARDED_TABLES = List.of(
            "cart_item",
            "cart_user_lock",
            "cart_merge_request",
            "trade_order",
            "order_item",
            "order_status_history",
            "order_address_snapshot",
            "order_benefit_selection",
            "order_price_snapshot",
            "order_discount_allocation",
            "after_sale_order",
            "after_sale_item",
            "after_sale_history",
            "outbox_event",
            "consumed_event",
            "consumer_failure",
            "reconciliation_record",
            "flash_sale_order_request",
            "distributed_id_worker_lease");

    @Bean
    @Primary
    public DataSource tradeShardingDataSource(TradeShardingProperties properties) throws SQLException {
        validate(properties);
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        try {
            for (TradeShardingProperties.Shard shard : properties.getShards()) {
                HikariDataSource dataSource = dataSource(shard, properties);
                migrate(dataSource);
                dataSources.put(shard.getName(), dataSource);
            }
            return createShardingDataSource(dataSources, properties.isSqlShow());
        } catch (RuntimeException | SQLException exception) {
            dataSources.values().forEach(TradeShardingDataSourceConfig::close);
            throw exception;
        }
    }

    static DataSource createShardingDataSource(
            Map<String, DataSource> dataSources,
            boolean sqlShow) throws SQLException {
        ShardingRuleConfiguration sharding = new ShardingRuleConfiguration();
        Collection<ShardingTableRuleConfiguration> tables = new ArrayList<>();
        for (String table : SHARDED_TABLES) {
            String actualDataNodes = dataSources.keySet().stream()
                    .map(dataSource -> dataSource + "." + table)
                    .collect(java.util.stream.Collectors.joining(","));
            tables.add(new ShardingTableRuleConfiguration(
                    table,
                    actualDataNodes));
        }
        sharding.setTables(tables);
        sharding.setDefaultDatabaseShardingStrategy(
                new HintShardingStrategyConfiguration("trade_database_hint"));
        Properties algorithmProperties = new Properties();
        algorithmProperties.setProperty("algorithm-expression", "ds_$->{value}");
        sharding.setShardingAlgorithms(Map.of(
                "trade_database_hint",
                new AlgorithmConfiguration("HINT_INLINE", algorithmProperties)));

        Properties globalProperties = new Properties();
        globalProperties.setProperty("sql-show", Boolean.toString(sqlShow));
        Collection<RuleConfiguration> rules = List.of(sharding);
        ModeConfiguration mode = new ModeConfiguration(
                "Standalone",
                new StandalonePersistRepositoryConfiguration("Memory", new Properties()));
        return ShardingSphereDataSourceFactory.createDataSource(
                "trade_sharding",
                mode,
                dataSources,
                rules,
                globalProperties);
    }

    private HikariDataSource dataSource(
            TradeShardingProperties.Shard shard,
            TradeShardingProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("trade-" + shard.getName());
        config.setDriverClassName(shard.getDriverClassName());
        config.setJdbcUrl(shard.getJdbcUrl());
        config.setUsername(shard.getUsername());
        config.setPassword(shard.getPassword());
        config.setConnectionTimeout(properties.getConnectionTimeout().toMillis());
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setMinimumIdle(properties.getMinimumIdle());
        config.setInitializationFailTimeout(1);
        return new HikariDataSource(config);
    }

    private void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();
    }

    static void validate(TradeShardingProperties properties) {
        int shardCount = properties.getShards().size();
        if (shardCount != 2 && shardCount != 4) {
            throw new IllegalArgumentException(
                    "M7 Trade sharding requires exactly two or four shards");
        }
        for (int index = 0; index < shardCount; index++) {
            TradeShardingProperties.Shard shard = properties.getShards().get(index);
            String expectedName = "ds_" + index;
            if (!expectedName.equals(shard.getName())) {
                throw new IllegalArgumentException(
                        "Trade shard names must be ordered from ds_0 to ds_"
                                + (shardCount - 1));
            }
            requireText(shard.getDriverClassName(), expectedName + ".driverClassName");
            requireText(shard.getJdbcUrl(), expectedName + ".jdbcUrl");
            requireText(shard.getUsername(), expectedName + ".username");
        }
        if (properties.getMaximumPoolSize() < 1
                || properties.getMinimumIdle() < 0
                || properties.getMinimumIdle() > properties.getMaximumPoolSize()) {
            throw new IllegalArgumentException("Invalid Trade shard pool sizing");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Trade shard property: " + name);
        }
    }

    private static void close(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception closeException) {
                log.warn("Failed to close a Trade shard datasource after startup failure",
                        closeException);
            }
        }
    }
}
