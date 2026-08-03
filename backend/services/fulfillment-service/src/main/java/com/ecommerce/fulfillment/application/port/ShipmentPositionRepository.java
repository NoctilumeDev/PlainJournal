package com.ecommerce.fulfillment.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShipmentPositionRepository {

    Optional<Position> findByFulfillmentIdForUpdate(Long fulfillmentId);

    Optional<Position> findByFulfillmentId(Long fulfillmentId);

    void insert(Position position);

    void update(Position position);

    List<NearbyPosition> findNearby(
            BigDecimal longitude,
            BigDecimal latitude,
            long radiusMeters,
            int limit);

    List<Position> listLatest(int limit);

    record Position(
            Long fulfillmentId,
            String fulfillmentNo,
            Long traceId,
            String externalEventId,
            String nodeType,
            String locationName,
            BigDecimal longitude,
            BigDecimal latitude,
            Instant occurredAt,
            Instant updatedAt
    ) {
    }

    record NearbyPosition(
            Position position,
            String orderNo,
            Long userId,
            String status,
            BigDecimal distanceMeters
    ) {
    }
}
