package com.ecommerce.inventory.interfaces.rest;

import com.ecommerce.inventory.application.model.InventoryModels.StockPosition;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory/admin")
public class AdminInventoryController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final InventoryService inventoryService;

    public AdminInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/warehouses")
    public ApiResponse<WarehouseView> createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {
        return ApiResponse.success(inventoryService.createWarehouse(request.code(), request.name()));
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseView>> warehouses() {
        return ApiResponse.success(inventoryService.listWarehouses());
    }

    @PostMapping("/stocks/adjustments")
    public ApiResponse<StockPosition> adjustStock(@Valid @RequestBody AdjustStockRequest request) {
        return ApiResponse.success(inventoryService.adjustStock(
                request.movementNo(), request.warehouseId(), request.skuId(),
                request.quantityDelta(), request.reason()));
    }

    @GetMapping("/warehouses/{warehouseId}/stocks/{skuId}")
    public ApiResponse<StockPosition> stockPosition(
            @PathVariable @Positive Long warehouseId,
            @PathVariable @Positive Long skuId) {
        return ApiResponse.success(inventoryService.getStockPosition(warehouseId, skuId));
    }

    public record CreateWarehouseRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String code,
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record AdjustStockRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String movementNo,
            @NotNull @Positive Long warehouseId,
            @NotNull @Positive Long skuId,
            @NotNull @Min(-1000000000L) @Max(1000000000L) Long quantityDelta,
            @NotBlank @Size(max = 240) String reason
    ) {
    }
}
