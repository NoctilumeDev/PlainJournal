package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.port.DistributedIdVerifier;
import com.ecommerce.trade.application.port.DistributedIdVerifier.DistributedIdSnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("m7-id-verification")
@Validated
@RestController
@RequestMapping("/api/v1/trade/status/distributed-id")
public class DistributedIdVerificationController {

    private final DistributedIdVerifier verifier;

    public DistributedIdVerificationController(
            DistributedIdVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping
    public ApiResponse<DistributedIdSnapshot> generate(
            @RequestParam(defaultValue = "1000") @Min(1) @Max(10_000) int count) {
        return ApiResponse.success(verifier.generate(count));
    }
}
