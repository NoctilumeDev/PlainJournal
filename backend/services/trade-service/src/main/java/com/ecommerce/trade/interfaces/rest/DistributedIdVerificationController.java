package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.trade.infrastructure.config.DistributedIdProperties;
import com.ecommerce.trade.infrastructure.id.DistributedIdWorkerLeaseManager;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("m7-id-verification")
@Validated
@RestController
@RequestMapping("/api/v1/trade/status/distributed-id")
public class DistributedIdVerificationController {

    private final DistributedIdGenerator generator;
    private final DistributedIdWorkerLeaseManager leaseManager;
    private final DistributedIdProperties properties;

    public DistributedIdVerificationController(
            DistributedIdGenerator generator,
            DistributedIdWorkerLeaseManager leaseManager,
            DistributedIdProperties properties) {
        this.generator = generator;
        this.leaseManager = leaseManager;
        this.properties = properties;
    }

    @GetMapping
    public ApiResponse<DistributedIdSnapshot> generate(
            @RequestParam(defaultValue = "1000") @Min(1) @Max(10_000) int count) {
        List<Long> ids = generator.nextIds(count);
        return ApiResponse.success(new DistributedIdSnapshot(
                properties.instanceId(),
                properties.namespace(),
                leaseManager.workerId(),
                ids));
    }

    public record DistributedIdSnapshot(
            String instanceId,
            String namespace,
            int workerId,
            List<Long> ids) {
    }
}
