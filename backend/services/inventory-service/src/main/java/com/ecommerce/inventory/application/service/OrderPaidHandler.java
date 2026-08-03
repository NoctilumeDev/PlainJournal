package com.ecommerce.inventory.application.service;

import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationView;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReservationMapper;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPaidHandler {

    public static final String CONSUMER_GROUP = "inventory-order-paid-v1";

    private final ConsumedEventMapper consumedEventMapper;
    private final InventoryReservationMapper reservationMapper;
    private final InventoryService inventoryService;

    public OrderPaidHandler(
            ConsumedEventMapper consumedEventMapper,
            InventoryReservationMapper reservationMapper,
            InventoryService inventoryService) {
        this.consumedEventMapper = consumedEventMapper;
        this.reservationMapper = reservationMapper;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public void handle(OrderPaidCommand command) {
        String payloadFingerprint = PayloadFingerprint.of(command.orderNo(), command.reservationNo());
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(), CONSUMER_GROUP, payloadFingerprint,
                reservationMapper.currentTime()) != 1) {
            String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                    command.eventId(), CONSUMER_GROUP);
            if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        ReservationView reservation = inventoryService.getReservation(command.reservationNo());
        if (!reservation.orderNo().equals(command.orderNo())) {
            throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
        }
        ReservationView confirmed =
                inventoryService.confirmReservation(command.reservationNo());
        if (!"CONFIRMED".equals(confirmed.status())) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
    }

    public record OrderPaidCommand(String eventId, String orderNo, String reservationNo) {
    }
}
