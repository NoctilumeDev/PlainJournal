package com.ecommerce.catalog.interfaces.rest;

import com.ecommerce.catalog.application.model.SearchModels.ProductSearchPage;
import com.ecommerce.catalog.application.service.CatalogProductSearchService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/catalog/search")
public class PublicSearchController {

    private final CatalogProductSearchService service;

    public PublicSearchController(CatalogProductSearchService service) {
        this.service = service;
    }

    @GetMapping("/products")
    public ApiResponse<ProductSearchPage> products(
            @RequestParam("q") @NotBlank @Size(max = 80) String query,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) @Positive Long categoryId) {
        return ApiResponse.success(service.search(query, page, size, categoryId));
    }
}
