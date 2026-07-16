package com.ecommerce.inventory.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("inventorySystemStatusController")
@RequestMapping("/api/v1/inventory")
public class SystemStatusController {

    private final String configurationSource;

    public SystemStatusController(
            @Value("${ecommerce.foundation.configuration-source:local-default}") String configurationSource) {
        this.configurationSource = configurationSource;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(new SystemStatusResponse("inventory-service", "UP", configurationSource));
    }

    public record SystemStatusResponse(String service, String status, String configurationSource) {
    }
}
