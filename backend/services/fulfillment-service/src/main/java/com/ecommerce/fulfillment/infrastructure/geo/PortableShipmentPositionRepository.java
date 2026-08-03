package com.ecommerce.fulfillment.infrastructure.geo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentOrderEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ShipmentLatestPositionEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.FulfillmentOrderMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ShipmentLatestPositionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(
        prefix = "ecommerce.fulfillment.geo",
        name = "mysql-spatial-enabled",
        havingValue = "false")
public class PortableShipmentPositionRepository implements ShipmentPositionRepository {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8d;

    private final ShipmentLatestPositionMapper positionMapper;
    private final FulfillmentOrderMapper orderMapper;

    public PortableShipmentPositionRepository(
            ShipmentLatestPositionMapper positionMapper,
            FulfillmentOrderMapper orderMapper) {
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public Optional<Position> findByFulfillmentIdForUpdate(Long fulfillmentId) {
        return Optional.ofNullable(positionMapper.selectByFulfillmentIdForUpdate(fulfillmentId))
                .map(this::toPosition);
    }

    @Override
    public Optional<Position> findByFulfillmentId(Long fulfillmentId) {
        return Optional.ofNullable(positionMapper.selectById(fulfillmentId))
                .map(this::toPosition);
    }

    @Override
    public void insert(Position position) {
        requireSingleRow(positionMapper.insert(toEntity(position)));
    }

    @Override
    public void update(Position position) {
        requireSingleRow(positionMapper.updateById(toEntity(position)));
    }

    @Override
    public List<NearbyPosition> findNearby(
            BigDecimal longitude,
            BigDecimal latitude,
            long radiusMeters,
            int limit) {
        List<ShipmentLatestPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<ShipmentLatestPositionEntity>()
                        .orderByDesc(ShipmentLatestPositionEntity::getOccurredAt)
                        .orderByDesc(ShipmentLatestPositionEntity::getFulfillmentId));
        if (positions.isEmpty()) {
            return List.of();
        }
        Map<Long, FulfillmentOrderEntity> orders = orderMapper.selectByIds(
                        positions.stream()
                                .map(ShipmentLatestPositionEntity::getFulfillmentId)
                                .toList())
                .stream()
                .collect(Collectors.toMap(FulfillmentOrderEntity::getId, Function.identity()));
        return positions.stream()
                .map(position -> nearby(position, orders.get(position.getFulfillmentId()),
                        longitude, latitude))
                .filter(item -> item != null
                        && item.distanceMeters().compareTo(BigDecimal.valueOf(radiusMeters)) <= 0)
                .sorted((left, right) -> {
                    int distance = left.distanceMeters().compareTo(right.distanceMeters());
                    if (distance != 0) {
                        return distance;
                    }
                    return right.position().occurredAt().compareTo(left.position().occurredAt());
                })
                .limit(limit)
                .toList();
    }

    @Override
    public List<Position> listLatest(int limit) {
        return positionMapper.selectLatest(limit).stream().map(this::toPosition).toList();
    }

    private NearbyPosition nearby(
            ShipmentLatestPositionEntity entity,
            FulfillmentOrderEntity order,
            BigDecimal longitude,
            BigDecimal latitude) {
        if (order == null) {
            return null;
        }
        BigDecimal distance = BigDecimal.valueOf(distanceMeters(
                        latitude.doubleValue(), longitude.doubleValue(),
                        entity.getLatitude().doubleValue(), entity.getLongitude().doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
        return new NearbyPosition(
                toPosition(entity),
                order.getOrderNo(),
                order.getUserId(),
                order.getStatus(),
                distance);
    }

    private double distanceMeters(
            double sourceLatitude,
            double sourceLongitude,
            double targetLatitude,
            double targetLongitude) {
        double latitudeDelta = Math.toRadians(targetLatitude - sourceLatitude);
        double longitudeDelta = Math.toRadians(targetLongitude - sourceLongitude);
        double sourceLatitudeRadians = Math.toRadians(sourceLatitude);
        double targetLatitudeRadians = Math.toRadians(targetLatitude);
        double haversine = Math.sin(latitudeDelta / 2.0d) * Math.sin(latitudeDelta / 2.0d)
                + Math.cos(sourceLatitudeRadians) * Math.cos(targetLatitudeRadians)
                * Math.sin(longitudeDelta / 2.0d) * Math.sin(longitudeDelta / 2.0d);
        return EARTH_RADIUS_METERS * 2.0d
                * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0d - haversine));
    }

    private Position toPosition(ShipmentLatestPositionEntity entity) {
        return new Position(
                entity.getFulfillmentId(),
                entity.getFulfillmentNo(),
                entity.getTraceId(),
                entity.getExternalEventId(),
                entity.getNodeType(),
                entity.getLocationName(),
                entity.getLongitude(),
                entity.getLatitude(),
                entity.getOccurredAt(),
                entity.getUpdatedAt());
    }

    private ShipmentLatestPositionEntity toEntity(Position position) {
        ShipmentLatestPositionEntity entity = new ShipmentLatestPositionEntity();
        entity.setFulfillmentId(position.fulfillmentId());
        entity.setFulfillmentNo(position.fulfillmentNo());
        entity.setTraceId(position.traceId());
        entity.setExternalEventId(position.externalEventId());
        entity.setNodeType(position.nodeType());
        entity.setLocationName(position.locationName());
        entity.setLongitude(position.longitude());
        entity.setLatitude(position.latitude());
        entity.setOccurredAt(position.occurredAt());
        entity.setUpdatedAt(position.updatedAt());
        return entity;
    }

    private void requireSingleRow(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Shipment position write affected an unexpected row count");
        }
    }
}
