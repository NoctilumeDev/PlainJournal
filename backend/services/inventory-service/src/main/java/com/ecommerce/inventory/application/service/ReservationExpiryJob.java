package com.ecommerce.inventory.application.service;

import com.ecommerce.inventory.infrastructure.config.InventorySchedulingConfig;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.infrastructure.config.ReservationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.inventory.reservation",
        name = "expiry-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final InventoryService inventoryService;
    private final ReservationProperties properties;

    public ReservationExpiryJob(InventoryService inventoryService, ReservationProperties properties) {
        this.inventoryService = inventoryService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.inventory.reservation.expiry-scan-delay:5000}",
            scheduler = InventorySchedulingConfig.CONTROL_SCHEDULER)
    public void expireReservations() {
        for (String reservationNo : inventoryService.findExpiredReservationNumbers(properties.expiryBatchSize())) {
            try {
                inventoryService.expireReservation(reservationNo);
            } catch (InventoryException exception) {
                log.debug("Reservation expiration lost a state race: reservationNo={}, code={}",
                        reservationNo, exception.error().code());
            }
        }
    }
}
