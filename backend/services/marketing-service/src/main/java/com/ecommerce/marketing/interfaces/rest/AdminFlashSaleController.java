package com.ecommerce.marketing.interfaces.rest;

import com.ecommerce.marketing.application.model.FlashSaleModels.CreateFlashSaleCommand;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleActivityView;
import com.ecommerce.marketing.application.service.FlashSaleService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@Validated
@RestController
@RequestMapping("/api/v1/marketing/admin/flash-sales")
public class AdminFlashSaleController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final FlashSaleService flashSaleService;

    public AdminFlashSaleController(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @PostMapping
    public ApiResponse<FlashSaleActivityView> create(
            @Valid @RequestBody CreateFlashSaleRequest request) {
        return ApiResponse.success(flashSaleService.create(request.toCommand()));
    }

    @PostMapping("/{activityNo}/publish")
    public ApiResponse<FlashSaleActivityView> publish(
            @PathVariable @Size(max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String activityNo) {
        return ApiResponse.success(flashSaleService.publish(activityNo));
    }

    public record CreateFlashSaleRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Positive Long productId,
            @NotNull @Positive Long skuId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal salePrice,
            @Positive int admissionLimit,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt
    ) {
        CreateFlashSaleCommand toCommand() {
            return new CreateFlashSaleCommand(
                    name,
                    productId,
                    skuId,
                    salePrice,
                    admissionLimit,
                    startsAt,
                    endsAt);
        }
    }
}
