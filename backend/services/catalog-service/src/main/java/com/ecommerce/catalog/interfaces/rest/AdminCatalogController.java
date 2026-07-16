package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.CatalogModels.BrandView;
import com.ecommerce.catalog.application.model.CatalogModels.CategoryView;
import com.ecommerce.catalog.application.model.CatalogModels.CreateProductCommand;
import com.ecommerce.catalog.application.model.CatalogModels.CreateSkuCommand;
import com.ecommerce.catalog.application.model.CatalogModels.MediaView;
import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.model.CatalogModels.SkuView;
import com.ecommerce.catalog.application.model.CatalogModels.UpdateProductCommand;
import com.ecommerce.catalog.application.model.CatalogModels.UpdateSkuCommand;
import com.ecommerce.catalog.application.model.CatalogModels.UploadIntent;
import com.ecommerce.catalog.application.service.CatalogService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog/admin")
public class AdminCatalogController {

    private static final String SLUG_PATTERN = "[a-z0-9]+(?:-[a-z0-9]+)*";

    private final CatalogService catalogService;

    public AdminCatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryView> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        return ApiResponse.success(catalogService.createCategory(
                request.parentId(), request.name(), request.slug(), request.sortOrder()));
    }

    @PostMapping("/brands")
    public ApiResponse<BrandView> createBrand(@Valid @RequestBody CreateBrandRequest request) {
        return ApiResponse.success(catalogService.createBrand(request.name(), request.slug()));
    }

    @PostMapping("/products")
    public ApiResponse<ProductDetail> createProduct(@Valid @RequestBody CreateProductRequest request) {
        List<CreateSkuCommand> skus = request.skus().stream()
                .map(sku -> new CreateSkuCommand(sku.skuCode(), sku.name(), sku.specJson(),
                        sku.salePrice(), sku.marketPrice()))
                .toList();
        return ApiResponse.success(catalogService.createProduct(new CreateProductCommand(
                request.categoryId(), request.brandId(), request.title(), request.subtitle(),
                request.description(), skus)));
    }

    @PutMapping("/products/{productId}")
    public ApiResponse<ProductDetail> updateProduct(
            @PathVariable @Positive Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.success(catalogService.updateProduct(productId, new UpdateProductCommand(
                request.categoryId(), request.brandId(), request.title(), request.subtitle(),
                request.description(), request.expectedVersion())));
    }

    @PostMapping("/products/{productId}/publish")
    public ApiResponse<ProductDetail> publish(
            @PathVariable @Positive Long productId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(catalogService.publishProduct(productId, request.expectedVersion()));
    }

    @PostMapping("/products/{productId}/unpublish")
    public ApiResponse<ProductDetail> unpublish(
            @PathVariable @Positive Long productId,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.success(catalogService.unpublishProduct(productId, request.expectedVersion()));
    }

    @PutMapping("/products/{productId}/skus/{skuId}")
    public ApiResponse<SkuView> updateSku(
            @PathVariable @Positive Long productId,
            @PathVariable @Positive Long skuId,
            @Valid @RequestBody UpdateSkuRequest request) {
        return ApiResponse.success(catalogService.updateSku(productId, skuId, new UpdateSkuCommand(
                request.name(), request.specJson(), request.salePrice(), request.marketPrice(),
                request.status(), request.expectedVersion())));
    }

    @PostMapping("/products/{productId}/media/upload-intents")
    public ApiResponse<UploadIntent> createUploadIntent(
            @PathVariable @Positive Long productId,
            @Valid @RequestBody UploadIntentRequest request) {
        return ApiResponse.success(catalogService.createUploadIntent(
                productId, request.contentType(), request.sizeBytes()));
    }

    @PostMapping("/products/{productId}/media")
    public ApiResponse<MediaView> confirmMedia(
            @PathVariable @Positive Long productId,
            @Valid @RequestBody ConfirmMediaRequest request) {
        return ApiResponse.success(catalogService.confirmMedia(
                productId, request.skuId(), request.objectKey(), request.sortOrder()));
    }

    public record CreateCategoryRequest(
            @Positive Long parentId,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 100) @Pattern(regexp = SLUG_PATTERN) String slug,
            @Min(0) @Max(100000) int sortOrder
    ) {
    }

    public record CreateBrandRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 100) @Pattern(regexp = SLUG_PATTERN) String slug
    ) {
    }

    public record CreateProductRequest(
            @NotNull @Positive Long categoryId,
            @NotNull @Positive Long brandId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 240) String subtitle,
            @Size(max = 10000) String description,
            @NotEmpty @Size(max = 100) List<@Valid CreateSkuRequest> skus
    ) {
    }

    public record CreateSkuRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String skuCode,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 2000) String specJson,
            @NotNull @DecimalMin(value = "0.01") BigDecimal salePrice,
            @DecimalMin(value = "0.01") BigDecimal marketPrice
    ) {
    }

    public record UpdateProductRequest(
            @NotNull @Positive Long categoryId,
            @NotNull @Positive Long brandId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 240) String subtitle,
            @Size(max = 10000) String description,
            @PositiveOrZero int expectedVersion
    ) {
    }

    public record UpdateSkuRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 2000) String specJson,
            @NotNull @DecimalMin(value = "0.01") BigDecimal salePrice,
            @DecimalMin(value = "0.01") BigDecimal marketPrice,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @PositiveOrZero int expectedVersion
    ) {
    }

    public record VersionRequest(@PositiveOrZero int expectedVersion) {
    }

    public record UploadIntentRequest(
            @NotBlank @Pattern(regexp = "image/(jpeg|png|webp)") String contentType,
            @Positive long sizeBytes
    ) {
    }

    public record ConfirmMediaRequest(
            @Positive Long skuId,
            @NotBlank @Size(max = 500) String objectKey,
            @Min(0) @Max(100000) int sortOrder
    ) {
    }
}
