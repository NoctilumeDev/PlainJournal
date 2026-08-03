package com.ecommerce.fulfillment.application.port;

import com.ecommerce.fulfillment.application.port.ShipmentPositionRepository.Position;

import java.util.List;
import java.util.Optional;

public interface ShipmentPositionCache {

    Optional<Position> get(String fulfillmentNo);

    void put(Position position);

    int rebuild(List<Position> positions);
}
