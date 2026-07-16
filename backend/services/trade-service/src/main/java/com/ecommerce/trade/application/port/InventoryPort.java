package com.ecommerce.trade.application.port;

import java.time.Instant;
import java.util.List;

public interface InventoryPort {

    WarehouseSnapshot getWarehouse(String code);

    ReservationSnapshot reserve(ReservationCommand command);

    ReservationSnapshot getReservation(String reservationNo);

    ReservationSnapshot release(String reservationNo);

    record WarehouseSnapshot(Long id, String code, String status) {
    }

    record ReservationLine(Long skuId, long quantity) {
    }

    record ReservationCommand(
            String reservationNo,
            String orderNo,
            Long warehouseId,
            Instant expiresAt,
            List<ReservationLine> items
    ) {
        public ReservationCommand {
            items = List.copyOf(items);
        }
    }

    record ReservationSnapshot(String reservationNo, String status, Long warehouseId) {
    }
}
