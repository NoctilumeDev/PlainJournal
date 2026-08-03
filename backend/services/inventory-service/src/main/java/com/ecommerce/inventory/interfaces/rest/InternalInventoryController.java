package com.ecommerce.inventory.interfaces.rest;

import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationView;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory/internal")
public class InternalInventoryController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final InventoryService inventoryService;

    public InternalInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/reservations")
    public ApiResponse<ReservationView> reserve(@Valid @RequestBody ReserveRequest request) {
        List<ReservationLineCommand> items = request.items().stream()
                .map(item -> new ReservationLineCommand(item.skuId(), item.quantity()))
                .toList();
        return ApiResponse.success(inventoryService.reserve(new ReserveInventoryCommand(
                request.reservationNo(), request.orderNo(), request.warehouseId(), request.expiresAt(), items)));
    }

    @GetMapping("/warehouses/{code}")
    public ApiResponse<WarehouseView> warehouse(
            @PathVariable @Pattern(regexp = "[A-Z0-9_-]{2,32}") String code) {
        return ApiResponse.success(inventoryService.getActiveWarehouseByCode(code));
    }

    @GetMapping("/reservations/{reservationNo}")
    public ApiResponse<ReservationView> reservation(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String reservationNo) {
        return ApiResponse.success(inventoryService.getReservation(reservationNo));
    }

    @PostMapping("/reservations/{reservationNo}/confirm")
    public ApiResponse<ReservationView> confirm(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String reservationNo) {
        return ApiResponse.success(inventoryService.confirmReservation(reservationNo));
    }

    @PostMapping("/reservations/{reservationNo}/release")
    public ApiResponse<ReservationView> release(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String reservationNo) {
        return ApiResponse.success(inventoryService.releaseReservation(reservationNo));
    }

    @PostMapping("/reservations/{reservationNo}/expire")
    public ApiResponse<ReservationView> expire(
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String reservationNo) {
        return ApiResponse.success(inventoryService.expireReservation(reservationNo));
    }

    public record ReserveRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String reservationNo,
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo,
            @NotNull @Positive Long warehouseId,
            @NotNull @Future Instant expiresAt,
            @NotEmpty @Size(max = 100) List<@Valid ReserveItemRequest> items
    ) {
    }

    public record ReserveItemRequest(
            @NotNull @Positive Long skuId,
            @Positive @Max(1000000000L) long quantity
    ) {
    }
}
