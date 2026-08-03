package com.ecommerce.fulfillment.infrastructure.geo;

import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "ecommerce.fulfillment.geo",
        name = "mysql-spatial-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MySqlShipmentPositionRepository implements ShipmentPositionRepository {

    private static final String POSITION_COLUMNS = """
            fulfillment_id, fulfillment_no, trace_id, external_event_id, node_type,
            location_name, longitude, latitude, occurred_at, updated_at
            """;
    private static final String QUERY_POINT = """
            ST_GeomFromText(
                CONCAT('POINT(', ?, ' ', ?, ')'),
                4326,
                'axis-order=long-lat')
            """;

    private final JdbcTemplate jdbcTemplate;

    public MySqlShipmentPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Position> findByFulfillmentIdForUpdate(Long fulfillmentId) {
        return first(jdbcTemplate.query("""
                SELECT __POSITION_COLUMNS__
                FROM shipment_latest_position
                WHERE fulfillment_id = ?
                FOR UPDATE
                """.replace("__POSITION_COLUMNS__", POSITION_COLUMNS),
                this::mapPosition, fulfillmentId));
    }

    @Override
    public Optional<Position> findByFulfillmentId(Long fulfillmentId) {
        return first(jdbcTemplate.query("""
                SELECT __POSITION_COLUMNS__
                FROM shipment_latest_position
                WHERE fulfillment_id = ?
                """.replace("__POSITION_COLUMNS__", POSITION_COLUMNS),
                this::mapPosition, fulfillmentId));
    }

    @Override
    public void insert(Position position) {
        int rows = jdbcTemplate.update("""
                INSERT INTO shipment_latest_position (
                    fulfillment_id, fulfillment_no, trace_id, external_event_id,
                    node_type, location_name, longitude, latitude, coordinates,
                    occurred_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, __QUERY_POINT__, ?, ?)
                """.replace("__QUERY_POINT__", QUERY_POINT),
                position.fulfillmentId(), position.fulfillmentNo(), position.traceId(),
                position.externalEventId(), position.nodeType(), position.locationName(),
                position.longitude(), position.latitude(),
                position.longitude(), position.latitude(),
                position.occurredAt(), position.updatedAt());
        requireSingleRow(rows);
    }

    @Override
    public void update(Position position) {
        int rows = jdbcTemplate.update("""
                UPDATE shipment_latest_position
                SET fulfillment_no = ?,
                    trace_id = ?,
                    external_event_id = ?,
                    node_type = ?,
                    location_name = ?,
                    longitude = ?,
                    latitude = ?,
                    coordinates = __QUERY_POINT__,
                    occurred_at = ?,
                    updated_at = ?
                WHERE fulfillment_id = ?
                """.replace("__QUERY_POINT__", QUERY_POINT),
                position.fulfillmentNo(), position.traceId(), position.externalEventId(),
                position.nodeType(), position.locationName(),
                position.longitude(), position.latitude(),
                position.longitude(), position.latitude(),
                position.occurredAt(), position.updatedAt(), position.fulfillmentId());
        requireSingleRow(rows);
    }

    @Override
    public List<NearbyPosition> findNearby(
            BigDecimal longitude,
            BigDecimal latitude,
            long radiusMeters,
            int limit) {
        return jdbcTemplate.query("""
                SELECT candidate.*
                FROM (
                    SELECT p.fulfillment_id, p.fulfillment_no, p.trace_id,
                           p.external_event_id, p.node_type, p.location_name,
                           p.longitude, p.latitude, p.occurred_at, p.updated_at,
                           o.order_no, o.user_id, o.status,
                           ST_Distance_Sphere(
                               p.coordinates,
                               __QUERY_POINT__) AS distance_meters
                    FROM shipment_latest_position p
                    JOIN fulfillment_order o ON o.id = p.fulfillment_id
                ) candidate
                WHERE candidate.distance_meters <= ?
                ORDER BY candidate.distance_meters ASC,
                         candidate.occurred_at DESC,
                         candidate.fulfillment_id DESC
                LIMIT ?
                """.replace("__QUERY_POINT__", QUERY_POINT),
                (resultSet, rowNum) -> new NearbyPosition(
                        mapPosition(resultSet, rowNum),
                        resultSet.getString("order_no"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("status"),
                        resultSet.getBigDecimal("distance_meters")),
                longitude, latitude, radiusMeters, limit);
    }

    @Override
    public List<Position> listLatest(int limit) {
        return jdbcTemplate.query("""
                SELECT __POSITION_COLUMNS__
                FROM shipment_latest_position
                ORDER BY occurred_at DESC, fulfillment_id DESC
                LIMIT ?
                """.replace("__POSITION_COLUMNS__", POSITION_COLUMNS),
                this::mapPosition, limit);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private Position mapPosition(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Position(
                resultSet.getLong("fulfillment_id"),
                resultSet.getString("fulfillment_no"),
                resultSet.getLong("trace_id"),
                resultSet.getString("external_event_id"),
                resultSet.getString("node_type"),
                resultSet.getString("location_name"),
                resultSet.getBigDecimal("longitude"),
                resultSet.getBigDecimal("latitude"),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "updated_at"));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Optional<Position> first(List<Position> positions) {
        return positions.stream().findFirst();
    }

    private void requireSingleRow(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Shipment position write affected an unexpected row count");
        }
    }
}
