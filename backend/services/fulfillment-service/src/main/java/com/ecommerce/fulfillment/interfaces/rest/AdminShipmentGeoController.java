package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.GeoCacheRebuildView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.NearbyShipmentPositionView;
import com.ecommerce.fulfillment.application.service.ShipmentGeoService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/admin/geo")
public class AdminShipmentGeoController {

    private final ShipmentGeoService shipmentGeoService;

    public AdminShipmentGeoController(ShipmentGeoService shipmentGeoService) {
        this.shipmentGeoService = shipmentGeoService;
    }

    @GetMapping("/nearby")
    public ApiResponse<List<NearbyShipmentPositionView>> nearby(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @RequestParam(defaultValue = "50000") @Min(1) @Max(5_000_000) long radiusMeters,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return ApiResponse.success(
                shipmentGeoService.nearby(longitude, latitude, radiusMeters, limit));
    }

    @PostMapping("/cache/rebuild")
    public ApiResponse<GeoCacheRebuildView> rebuildCache(
            @RequestParam(defaultValue = "5000") @Min(1) @Max(100_000) int limit) {
        return ApiResponse.success(shipmentGeoService.rebuildCache(limit));
    }
}
