package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("catalogSystemStatusController")
@RequestMapping("/api/v1/catalog")
public class SystemStatusController {

    private final String configurationSource;

    public SystemStatusController(
            @Value("${ecommerce.foundation.configuration-source:local-default}") String configurationSource) {
        this.configurationSource = configurationSource;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(new SystemStatusResponse("catalog-service", "UP", configurationSource));
    }

    public record SystemStatusResponse(String service, String status, String configurationSource) {
    }
}
