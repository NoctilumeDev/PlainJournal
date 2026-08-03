package com.ecommerce.fulfillment.infrastructure.geo;

import com.ecommerce.fulfillment.application.port.ShipmentPositionCache;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository.Position;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.fulfillment.geo",
        name = "cache-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedisShipmentPositionCache implements ShipmentPositionCache {

    private static final BigDecimal COORDINATE_TOLERANCE = new BigDecimal("0.000001");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ShipmentGeoProperties properties;

    public RedisShipmentPositionCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ShipmentGeoProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<Position> get(String fulfillmentNo) {
        String metadata = redisTemplate.opsForValue().get(
                properties.redisMetadataKey(fulfillmentNo));
        if (metadata == null) {
            return Optional.empty();
        }
        List<Point> points = redisTemplate.opsForGeo().position(
                properties.redisGeoKey(), fulfillmentNo);
        if (points == null || points.isEmpty() || points.get(0) == null) {
            return Optional.empty();
        }
        Position position = read(metadata);
        Point point = points.get(0);
        if (!fulfillmentNo.equals(position.fulfillmentNo())
                || !sameCoordinate(position.longitude(), BigDecimal.valueOf(point.getX()))
                || !sameCoordinate(position.latitude(), BigDecimal.valueOf(point.getY()))) {
            return Optional.empty();
        }
        return Optional.of(position);
    }

    @Override
    public void put(Position position) {
        redisTemplate.opsForValue().set(
                properties.redisMetadataKey(position.fulfillmentNo()),
                write(position));
        Long updated = redisTemplate.opsForGeo().add(
                properties.redisGeoKey(),
                new Point(position.longitude().doubleValue(), position.latitude().doubleValue()),
                position.fulfillmentNo());
        if (updated == null) {
            throw new IllegalStateException("Redis GEO update returned no result");
        }
    }

    @Override
    public int rebuild(List<Position> positions) {
        for (Position position : positions) {
            put(position);
        }
        return positions.size();
    }

    private Position read(String value) {
        try {
            return objectMapper.readValue(value, Position.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis GEO metadata is invalid", exception);
        }
    }

    private String write(Position position) {
        try {
            return objectMapper.writeValueAsString(position);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Redis GEO metadata", exception);
        }
    }

    private boolean sameCoordinate(BigDecimal expected, BigDecimal actual) {
        return expected.subtract(actual).abs().compareTo(COORDINATE_TOLERANCE) <= 0;
    }
}
