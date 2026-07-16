package com.ecommerce.catalog.application.model;

import java.math.BigDecimal;
import java.util.List;

public final class CatalogModels {

    private CatalogModels() {
    }

    public record CategoryView(Long id, Long parentId, String name, String slug, int sortOrder) {
    }

    public record BrandView(Long id, String name, String slug) {
    }

    public record SkuView(
            Long id,
            String skuCode,
            String name,
            String specJson,
            BigDecimal salePrice,
            BigDecimal marketPrice,
            String status,
            int version
    ) {
    }

    public record MediaView(
            Long id,
            Long skuId,
            String objectKey,
            String mimeType,
            long sizeBytes,
            int sortOrder,
            String url
    ) {
    }

    public record ProductSummary(
            Long id,
            String title,
            String subtitle,
            CategoryView category,
            BrandView brand,
            BigDecimal minimumPrice,
            String coverUrl
    ) {
    }

    public record ProductDetail(
            Long id,
            String title,
            String subtitle,
            String description,
            String status,
            int version,
            CategoryView category,
            BrandView brand,
            List<SkuView> skus,
            List<MediaView> media
    ) {
        public ProductDetail {
            skus = List.copyOf(skus);
            media = List.copyOf(media);
        }
    }

    public record CreateSkuCommand(
            String skuCode,
            String name,
            String specJson,
            BigDecimal salePrice,
            BigDecimal marketPrice
    ) {
    }

    public record CreateProductCommand(
            Long categoryId,
            Long brandId,
            String title,
            String subtitle,
            String description,
            List<CreateSkuCommand> skus
    ) {
        public CreateProductCommand {
            skus = List.copyOf(skus);
        }
    }

    public record UpdateProductCommand(
            Long categoryId,
            Long brandId,
            String title,
            String subtitle,
            String description,
            int expectedVersion
    ) {
    }

    public record UpdateSkuCommand(
            String name,
            String specJson,
            BigDecimal salePrice,
            BigDecimal marketPrice,
            String status,
            int expectedVersion
    ) {
    }

    public record UploadIntent(String objectKey, String uploadUrl, long expiresInSeconds) {
    }
}
