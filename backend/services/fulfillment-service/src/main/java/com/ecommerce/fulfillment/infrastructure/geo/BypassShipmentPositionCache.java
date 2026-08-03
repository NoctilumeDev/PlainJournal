package com.ecommerce.fulfillment.infrastructure.geo;

import com.ecommerce.fulfillment.application.port.ShipmentPositionCache;
import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository.Position;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.fulfillment.geo",
        name = "cache-enabled",
        havingValue = "false")
public class BypassShipmentPositionCache implements ShipmentPositionCache {

    @Override
    public Optional<Position> get(String fulfillmentNo) {
        return Optional.empty();
    }

    @Override
    public void put(Position position) {
        // MySQL remains authoritative when the rebuildable Redis projection is disabled.
    }

    @Override
    public int rebuild(List<Position> positions) {
        return 0;
    }
}
