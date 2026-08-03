package com.ecommerce.trade;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TradePricingSnapshotMigrationTest {

    @Test
    void backfillsLegacyLineNumbersBeforeEnforcingTheSnapshotContract() throws Exception {
        String url = "jdbc:h2:mem:trade_upgrade_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO trade_order
                        (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
                         warehouse_code, warehouse_id, status, original_amount, discount_amount,
                         total_amount, payment_deadline, recovery_attempts, version, created_at, updated_at)
                    VALUES
                        (1, 'LEGACY-ORDER', 1, 'legacy-idem',
                         '0000000000000000000000000000000000000000000000000000000000000000',
                         'LEGACY-RES', 'PRIMARY', 1, 'COMPLETED', NULL, 0.00, 20.00,
                         CURRENT_TIMESTAMP, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO order_item
                        (id, order_id, line_no, product_id, sku_id, product_title, sku_code,
                         sku_name, spec_json, unit_price, quantity, line_amount, discount_amount,
                         payable_amount, created_at)
                    VALUES
                        (12, 1, NULL, 2, 102, 'Second', 'SKU-102', 'Second', '{}',
                         10.00, 1, 10.00, 0.00, NULL, CURRENT_TIMESTAMP),
                        (11, 1, NULL, 1, 101, 'First', 'SKU-101', 'First', '{}',
                         10.00, 1, 10.00, 2.00, NULL, CURRENT_TIMESTAMP)
                    """);
        }

        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, line_no, payable_amount FROM order_item ORDER BY id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("id")).isEqualTo(11L);
            assertThat(result.getInt("line_no")).isEqualTo(1);
            assertThat(result.getBigDecimal("payable_amount")).isEqualByComparingTo("8.00");
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("id")).isEqualTo(12L);
            assertThat(result.getInt("line_no")).isEqualTo(2);
            assertThat(result.getBigDecimal("payable_amount")).isEqualByComparingTo("10.00");
            assertThat(result.next()).isFalse();
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT original_amount FROM trade_order WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBigDecimal("original_amount")).isEqualByComparingTo("20.00");
        }
    }
}
