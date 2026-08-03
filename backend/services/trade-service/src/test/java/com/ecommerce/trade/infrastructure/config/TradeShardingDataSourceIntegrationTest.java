package com.ecommerce.trade.infrastructure.config;

import com.ecommerce.trade.infrastructure.sharding.HintTradeShardRouter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeShardingDataSourceIntegrationTest {

    private final Map<String, HikariDataSource> physicalDataSources = new LinkedHashMap<>();
    private DataSource shardingDataSource;
    private JdbcTemplate logical;
    private HintTradeShardRouter router;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        physicalDataSources.put("ds_0", physicalDataSource("trade_shard_0"));
        physicalDataSources.put("ds_1", physicalDataSource("trade_shard_1"));
        physicalDataSources.values().forEach(this::migrate);
        shardingDataSource = TradeShardingDataSourceConfig.createShardingDataSource(
                Map.copyOf(physicalDataSources), false);
        logical = new JdbcTemplate(shardingDataSource);
        router = new HintTradeShardRouter(2);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(shardingDataSource));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (shardingDataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
        physicalDataSources.values().forEach(HikariDataSource::close);
        physicalDataSources.clear();
    }

    @Test
    void routesUserOwnedCrossTableTransactionToExactlyOneShard() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        router.runForUser(2L, () -> transactionTemplate.executeWithoutResult(ignored -> {
            logical.update("""
                    INSERT INTO cart_user_lock (user_id, created_at, updated_at)
                    VALUES (?, ?, ?)
                    """, 2L, now, now);
            logical.update("""
                    INSERT INTO cart_merge_request
                        (id, user_id, merge_key, request_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, 2001L, 2L, "merge-user-2", "a".repeat(64), now);
        }));
        router.runForUser(3L, () -> transactionTemplate.executeWithoutResult(ignored -> {
            logical.update("""
                    INSERT INTO cart_user_lock (user_id, created_at, updated_at)
                    VALUES (?, ?, ?)
                    """, 3L, now, now);
            logical.update("""
                    INSERT INTO cart_merge_request
                        (id, user_id, merge_key, request_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, 3001L, 3L, "merge-user-3", "b".repeat(64), now);
        }));

        assertThat(countOnPhysicalShard(0, "cart_user_lock")).isOne();
        assertThat(countOnPhysicalShard(0, "cart_merge_request")).isOne();
        assertThat(countOnPhysicalShard(1, "cart_user_lock")).isOne();
        assertThat(countOnPhysicalShard(1, "cart_merge_request")).isOne();
        assertThat(logical.queryForObject(
                "SELECT COUNT(*) FROM cart_user_lock", Long.class)).isEqualTo(2L);
        assertThat(logical.queryForObject(
                "SELECT user_id FROM cart_user_lock WHERE user_id = ?",
                Long.class,
                3L)).isEqualTo(3L);
    }

    @Test
    void rollsBackOnlyTheSelectedShardAndDoesNotTouchTheOtherShard() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        assertThatThrownBy(() -> router.runForUser(
                2L,
                () -> transactionTemplate.executeWithoutResult(ignored -> {
                    logical.update("""
                            INSERT INTO cart_user_lock (user_id, created_at, updated_at)
                            VALUES (?, ?, ?)
                            """, 2L, now, now);
                    throw new IllegalStateException("rollback");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");

        assertThat(countOnPhysicalShard(0, "cart_user_lock")).isZero();
        assertThat(countOnPhysicalShard(1, "cart_user_lock")).isZero();
    }

    @Test
    void supportsFourOrderedShardsAndRoutesAllUserClasses() throws Exception {
        closeDataSources();
        for (int index = 0; index < 4; index++) {
            physicalDataSources.put(
                    "ds_" + index,
                    physicalDataSource("trade_reshard_" + index));
        }
        physicalDataSources.values().forEach(this::migrate);
        shardingDataSource = TradeShardingDataSourceConfig.createShardingDataSource(
                Map.copyOf(physicalDataSources), false);
        logical = new JdbcTemplate(shardingDataSource);
        router = new HintTradeShardRouter(4);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(shardingDataSource));
        Instant now = Instant.parse("2026-07-22T00:00:00Z");

        for (long userId = 4; userId < 8; userId++) {
            long routedUserId = userId;
            router.runForUser(routedUserId, () -> logical.update("""
                    INSERT INTO cart_user_lock (user_id, created_at, updated_at)
                    VALUES (?, ?, ?)
                    """, routedUserId, now, now));
        }

        for (int index = 0; index < 4; index++) {
            assertThat(countOnPhysicalShard(index, "cart_user_lock")).isOne();
        }
    }

    @Test
    void rejectsUnsupportedOrUnorderedShardSets() {
        TradeShardingProperties unsupported = properties(3);
        assertThatThrownBy(() -> TradeShardingDataSourceConfig.validate(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("M7 Trade sharding requires exactly two or four shards");

        TradeShardingProperties unordered = properties(4);
        unordered.getShards().get(3).setName("ds_4");
        assertThatThrownBy(() -> TradeShardingDataSourceConfig.validate(unordered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Trade shard names must be ordered from ds_0 to ds_3");
    }

    private TradeShardingProperties properties(int shardCount) {
        TradeShardingProperties properties = new TradeShardingProperties();
        java.util.List<TradeShardingProperties.Shard> shards = new java.util.ArrayList<>();
        for (int index = 0; index < shardCount; index++) {
            TradeShardingProperties.Shard shard = new TradeShardingProperties.Shard();
            shard.setName("ds_" + index);
            shard.setJdbcUrl("jdbc:h2:mem:validation_" + index);
            shard.setUsername("sa");
            shards.add(shard);
        }
        properties.setShards(shards);
        return properties;
    }

    private HikariDataSource physicalDataSource(String databaseName) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:mem:" + databaseName + "_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
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

    private long countOnPhysicalShard(int shardIndex, String table) {
        return new JdbcTemplate(physicalDataSources.get("ds_" + shardIndex))
                .queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void closeDataSources() throws Exception {
        if (shardingDataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
        shardingDataSource = null;
        physicalDataSources.values().forEach(HikariDataSource::close);
        physicalDataSources.clear();
    }
}
