package com.ecommerce.inventory.application.service;

import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationView;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumedEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OrderPaidHandler {

    public static final String CONSUMER_GROUP = "inventory-order-paid-v1";

    private final ConsumedEventMapper consumedEventMapper;
    private final InventoryService inventoryService;
    private final Clock clock;

    public OrderPaidHandler(
            ConsumedEventMapper consumedEventMapper,
            InventoryService inventoryService,
            Clock clock) {
        this.consumedEventMapper = consumedEventMapper;
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    @Transactional
    public void handle(OrderPaidCommand command) {
        if (consumedEventMapper.insertIfAbsent(command.eventId(), CONSUMER_GROUP, clock.instant()) != 1) {
            return;
        }
        ReservationView reservation = inventoryService.getReservation(command.reservationNo());
        if (!reservation.orderNo().equals(command.orderNo())) {
            throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
        }
        inventoryService.confirmReservation(command.reservationNo());
    }

    public record OrderPaidCommand(String eventId, String orderNo, String reservationNo) {
    }
}
