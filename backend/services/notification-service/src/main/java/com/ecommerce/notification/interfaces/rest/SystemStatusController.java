package com.ecommerce.notification.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("notificationSystemStatusController")
@RequestMapping("/api/v1/notifications")
public class SystemStatusController {

    private final String configurationSource;

    public SystemStatusController(
            @Value("${ecommerce.foundation.configuration-source:local-default}")
            String configurationSource) {
        this.configurationSource = configurationSource;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(new SystemStatusResponse(
                "notification-service",
                "UP",
                configurationSource));
    }

    public record SystemStatusResponse(
            String service,
            String status,
            String configurationSource) {
    }
}
