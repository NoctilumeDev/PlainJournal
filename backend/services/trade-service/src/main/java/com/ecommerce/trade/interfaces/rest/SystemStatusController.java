package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trade")
public class SystemStatusController {

    private final String configurationSource;

    public SystemStatusController(
            @Value("${ecommerce.foundation.configuration-source:local-default}") String configurationSource) {
        this.configurationSource = configurationSource;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(new SystemStatusResponse("trade-service", configurationSource));
    }

    public record SystemStatusResponse(String service, String configurationSource) {
    }
}
