package com.ecommerce.catalog.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class CatalogProductCreateCommandRepository {

    private final JdbcTemplate jdbc;

    public CatalogProductCreateCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(
            long operatorId,
            String commandId,
            String requestHash,
            long productId,
            Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO catalog_product_create_command
                    (operator_id, command_id, request_hash, product_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, operatorId, commandId, requestHash, productId, Timestamp.from(now)) == 1;
    }

    public CreateCommandState find(long operatorId, String commandId) {
        return find(operatorId, commandId, false);
    }

    public CreateCommandState findForUpdate(long operatorId, String commandId) {
        return find(operatorId, commandId, true);
    }

    private CreateCommandState find(long operatorId, String commandId, boolean forUpdate) {
        List<CreateCommandState> rows = jdbc.query("""
                SELECT operator_id, command_id, request_hash, product_id, created_at
                FROM catalog_product_create_command
                WHERE operator_id = ? AND command_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), (rs, rowNum) -> new CreateCommandState(
                rs.getLong("operator_id"),
                rs.getString("command_id"),
                rs.getString("request_hash"),
                rs.getLong("product_id"),
                rs.getTimestamp("created_at").toInstant()), operatorId, commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record CreateCommandState(
            long operatorId,
            String commandId,
            String requestHash,
            long productId,
            Instant createdAt) {
    }
}
