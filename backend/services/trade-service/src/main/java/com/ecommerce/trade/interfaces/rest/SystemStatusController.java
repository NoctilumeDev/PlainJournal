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
    private final String instanceId;
    private final String releaseId;

    public SystemStatusController(
            @Value("${ecommerce.foundation.configuration-source:local-default}") String configurationSource,
            @Value("${SERVICE_INSTANCE_ID:local}") String instanceId,
            @Value("${SERVICE_RELEASE_ID:local}") String releaseId) {
        this.configurationSource = configurationSource;
        this.instanceId = instanceId;
        this.releaseId = releaseId;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(
                new SystemStatusResponse("trade-service", configurationSource, instanceId, releaseId));
    }

    public record SystemStatusResponse(
            String service,
            String configurationSource,
            String instanceId,
            String releaseId) {
    }
}
