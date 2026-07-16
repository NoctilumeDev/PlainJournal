package com.ecommerce.fulfillment.interfaces.rest;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.AddTraceCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.FulfillmentView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ShipCommand;
import com.ecommerce.fulfillment.application.service.FulfillmentService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/fulfillment/admin/orders")
public class AdminFulfillmentController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final FulfillmentService fulfillmentService;

    public AdminFulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping
    public ApiResponse<List<FulfillmentView>> orders(@RequestParam(required = false) String status) {
        return ApiResponse.success(fulfillmentService.list(status));
    }

    @GetMapping("/{fulfillmentNo}")
    public ApiResponse<FulfillmentView> order(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo) {
        return ApiResponse.success(fulfillmentService.get(fulfillmentNo));
    }

    @PostMapping("/{fulfillmentNo}/picking")
    public ApiResponse<FulfillmentView> startPicking(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.startPicking(fulfillmentNo, jwt.getSubject()));
    }

    @PostMapping("/{fulfillmentNo}/packed")
    public ApiResponse<FulfillmentView> packed(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.markPacked(fulfillmentNo, jwt.getSubject()));
    }

    @PostMapping("/{fulfillmentNo}/ship")
    public ApiResponse<FulfillmentView> ship(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo,
            @Valid @RequestBody ShipRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.ship(fulfillmentNo,
                new ShipCommand(request.carrier(), request.trackingNo(), jwt.getSubject())));
    }

    @PostMapping("/{fulfillmentNo}/traces")
    public ApiResponse<FulfillmentView> addTrace(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo,
            @Valid @RequestBody AddTraceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.addTrace(fulfillmentNo, new AddTraceCommand(
                request.externalEventId(), request.nodeType(), request.description(), request.locationName(),
                request.longitude(), request.latitude(), request.occurredAt(), jwt.getSubject())));
    }

    @PostMapping("/{fulfillmentNo}/exception")
    public ApiResponse<FulfillmentView> markException(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN)
            String fulfillmentNo,
            @Valid @RequestBody ExceptionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(fulfillmentService.markException(
                fulfillmentNo, request.reason(), jwt.getSubject()));
    }

    public record ShipRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+") String carrier,
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String trackingNo
    ) {
    }

    public record AddTraceRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = BUSINESS_NO_PATTERN) String externalEventId,
            @NotBlank @Pattern(regexp = "TRANSIT|DELIVERING|SIGNED|EXCEPTION") String nodeType,
            @NotBlank @Size(max = 240) String description,
            @Size(max = 120) String locationName,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull Instant occurredAt
    ) {
    }

    public record ExceptionRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
