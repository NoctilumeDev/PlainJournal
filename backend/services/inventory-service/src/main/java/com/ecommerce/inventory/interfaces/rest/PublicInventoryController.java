package com.ecommerce.inventory.interfaces.rest;

import com.ecommerce.inventory.application.model.InventoryModels.StockSummary;
import com.ecommerce.inventory.application.service.InventoryService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/inventory")
public class PublicInventoryController {

    private final InventoryService inventoryService;

    public PublicInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/stocks/{skuId}")
    public ApiResponse<StockSummary> stock(@PathVariable @Positive Long skuId) {
        return ApiResponse.success(inventoryService.getStockSummary(skuId));
    }
}
