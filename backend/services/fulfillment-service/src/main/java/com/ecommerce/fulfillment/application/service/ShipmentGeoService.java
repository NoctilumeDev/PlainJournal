package com.ecommerce.fulfillment.application.service;

import com.ecommerce.fulfillment.application.exception.FulfillmentError;
import com.ecommerce.fulfillment.application.exception.FulfillmentException;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.GeoCacheRebuildView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.NearbyShipmentPositionView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ShipmentPositionView;
import com.ecommerce.fulfillment.application.port.ShipmentPositionCache;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository.NearbyPosition;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository.Position;
import com.ecommerce.fulfillment.infrastructure.geo.ShipmentGeoProperties;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentOrderEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.LogisticsTraceEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.FulfillmentOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ShipmentGeoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShipmentGeoService.class);

    private final FulfillmentOrderMapper orderMapper;
    private final ShipmentPositionRepository positionRepository;
    private final ShipmentPositionCache positionCache;
    private final ShipmentGeoProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public ShipmentGeoService(
            FulfillmentOrderMapper orderMapper,
            ShipmentPositionRepository positionRepository,
            ShipmentPositionCache positionCache,
            ShipmentGeoProperties properties,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.orderMapper = orderMapper;
        this.positionRepository = positionRepository;
        this.positionCache = positionCache;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    public void recordLatestPosition(
            FulfillmentOrderEntity order,
            LogisticsTraceEntity trace,
            Instant updatedAt) {
        if (trace.getLongitude() == null || trace.getLatitude() == null) {
            return;
        }
        Optional<Position> current = positionRepository.findByFulfillmentIdForUpdate(order.getId());
        if (current.isPresent() && !isNewer(trace, current.get())) {
            return;
        }
        Position position = new Position(
                order.getId(),
                order.getFulfillmentNo(),
                trace.getId(),
                trace.getExternalEventId(),
                trace.getNodeType(),
                trace.getLocationName(),
                trace.getLongitude(),
                trace.getLatitude(),
                trace.getOccurredAt(),
                updatedAt);
        if (current.isPresent()) {
            positionRepository.update(position);
        } else {
            positionRepository.insert(position);
        }
        order.setLatestPositionTraceId(trace.getId());
        order.setLatestPositionAt(trace.getOccurredAt());
        eventPublisher.publishEvent(new ShipmentPositionChanged(position));
    }

    public ShipmentPositionView latestForUser(String orderNo, Long userId) {
        FulfillmentOrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        if (order.getLatestPositionTraceId() == null) {
            throw new FulfillmentException(FulfillmentError.POSITION_NOT_AVAILABLE);
        }
        Optional<Position> cached = readCache(order);
        if (cached.isPresent()) {
            recordCacheMetric("read", "hit");
            return view(cached.get(), order.getOrderNo());
        }
        recordCacheMetric("read", "fallback");
        Position position = positionRepository.findByFulfillmentId(order.getId())
                .orElseThrow(() -> new FulfillmentException(FulfillmentError.POSITION_NOT_AVAILABLE));
        bestEffortCacheWrite(position, "read_repair");
        return view(position, order.getOrderNo());
    }

    public List<NearbyShipmentPositionView> nearby(
            BigDecimal longitude,
            BigDecimal latitude,
            long radiusMeters,
            int limit) {
        if (radiusMeters <= 0 || radiusMeters > properties.maxRadiusMeters()
                || limit <= 0 || limit > properties.maxResults()) {
            throw new FulfillmentException(FulfillmentError.INVALID_GEO_QUERY);
        }
        return positionRepository.findNearby(longitude, latitude, radiusMeters, limit)
                .stream()
                .map(this::nearbyView)
                .toList();
    }

    public GeoCacheRebuildView rebuildCache(int limit) {
        if (limit <= 0 || limit > properties.rebuildLimit()) {
            throw new FulfillmentException(FulfillmentError.INVALID_GEO_QUERY);
        }
        List<Position> positions = positionRepository.listLatest(limit);
        try {
            int cached = positionCache.rebuild(positions);
            recordCacheMetric("rebuild", "success");
            return new GeoCacheRebuildView(positions.size(), cached);
        } catch (RuntimeException exception) {
            recordCacheMetric("rebuild", "failure");
            LOGGER.warn("Shipment GEO cache rebuild failed: {}",
                    exception.getClass().getSimpleName());
            throw new FulfillmentException(FulfillmentError.GEO_CACHE_UNAVAILABLE);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cacheCommittedPosition(ShipmentPositionChanged event) {
        bestEffortCacheWrite(event.position(), "after_commit");
    }

    private Optional<Position> readCache(FulfillmentOrderEntity order) {
        try {
            return positionCache.get(order.getFulfillmentNo())
                    .filter(position -> position.traceId().equals(order.getLatestPositionTraceId()));
        } catch (RuntimeException exception) {
            recordCacheMetric("read", "failure");
            LOGGER.warn("Shipment GEO cache read failed for fulfillment {}: {}",
                    order.getFulfillmentNo(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void bestEffortCacheWrite(Position position, String operation) {
        try {
            positionCache.put(position);
            recordCacheMetric(operation, "success");
        } catch (RuntimeException exception) {
            recordCacheMetric(operation, "failure");
            LOGGER.warn("Shipment GEO cache update failed for fulfillment {}: {}",
                    position.fulfillmentNo(), exception.getClass().getSimpleName());
        }
    }

    private boolean isNewer(LogisticsTraceEntity trace, Position current) {
        int occurredAtOrder = trace.getOccurredAt().compareTo(current.occurredAt());
        return occurredAtOrder > 0
                || occurredAtOrder == 0 && trace.getId().compareTo(current.traceId()) > 0;
    }

    private ShipmentPositionView view(Position position, String orderNo) {
        return new ShipmentPositionView(
                position.fulfillmentNo(),
                orderNo,
                position.externalEventId(),
                position.nodeType(),
                position.locationName(),
                position.longitude(),
                position.latitude(),
                position.occurredAt());
    }

    private NearbyShipmentPositionView nearbyView(NearbyPosition nearby) {
        Position position = nearby.position();
        return new NearbyShipmentPositionView(
                position.fulfillmentNo(),
                nearby.orderNo(),
                nearby.userId(),
                nearby.status(),
                position.nodeType(),
                position.locationName(),
                position.longitude(),
                position.latitude(),
                nearby.distanceMeters(),
                position.occurredAt());
    }

    private void recordCacheMetric(String operation, String outcome) {
        meterRegistry.counter(
                "ecommerce.fulfillment.geo.cache.operations",
                "operation", operation,
                "outcome", outcome).increment();
    }

    public record ShipmentPositionChanged(Position position) {
    }
}
