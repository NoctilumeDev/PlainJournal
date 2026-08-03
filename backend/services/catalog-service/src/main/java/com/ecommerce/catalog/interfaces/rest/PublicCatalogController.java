package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.CatalogModels.BrandView;
import com.ecommerce.catalog.application.model.CatalogModels.CategoryView;
import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.model.CatalogModels.ProductSummary;
import com.ecommerce.catalog.application.routing.CatalogReplicaRead;
import com.ecommerce.catalog.application.service.CatalogService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.api.CursorPageResponse;
import com.ecommerce.platform.common.api.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/catalog")
public class PublicCatalogController {

    private final CatalogService catalogService;

    public PublicCatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/categories")
    @CatalogReplicaRead
    public ApiResponse<List<CategoryView>> categories() {
        return ApiResponse.success(catalogService.listCategories());
    }

    @GetMapping("/brands")
    @CatalogReplicaRead
    public ApiResponse<List<BrandView>> brands() {
        return ApiResponse.success(catalogService.listBrands());
    }

    @GetMapping("/products")
    @CatalogReplicaRead
    public ApiResponse<PageResponse<ProductSummary>> products(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 80) String keyword) {
        return ApiResponse.success(catalogService.listProducts(page, size, categoryId, keyword));
    }

    @GetMapping("/products/cursor")
    @CatalogReplicaRead
    public ApiResponse<CursorPageResponse<ProductSummary>> productsByCursor(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 80) String keyword,
            @RequestParam(required = false) @Size(max = 200) String cursor) {
        return ApiResponse.success(catalogService.listProductsByCursor(
                size, categoryId, keyword, cursor));
    }

    @GetMapping("/products/{productId}")
    @CatalogReplicaRead
    public ApiResponse<ProductDetail> product(@PathVariable @Positive Long productId) {
        return ApiResponse.success(catalogService.getProduct(productId));
    }
}
