package com.ecommerce.catalog.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.List;

public final class CatalogModels {

    private CatalogModels() {
    }

    public record CategoryView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long parentId,
            String name,
            String slug,
            int sortOrder
    ) {
    }

    public record BrandView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            String slug
    ) {
    }

    public record SkuView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
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
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long skuId,
            String objectKey,
            String mimeType,
            long sizeBytes,
            int sortOrder,
            String url
    ) {
    }

    public record ProductSummary(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String title,
            String subtitle,
            CategoryView category,
            BrandView brand,
            BigDecimal minimumPrice,
            String coverUrl
    ) {
    }

    public record ProductDetail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
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
