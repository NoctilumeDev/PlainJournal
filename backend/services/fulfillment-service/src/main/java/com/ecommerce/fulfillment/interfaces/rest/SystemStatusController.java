package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/fulfillment")
public class SystemStatusController {

    private final String applicationName;
    private final String configurationSource;

    public SystemStatusController(
            @Value("${spring.application.name}") String applicationName,
            @Value("${ecommerce.foundation.configuration-source:local-default}") String configurationSource) {
        this.applicationName = applicationName;
        this.configurationSource = configurationSource;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, String>> status() {
        return ApiResponse.success(Map.of(
                "service", applicationName,
                "status", "UP",
                "configurationSource", configurationSource));
    }
}
