package com.ecommerce.catalog.infrastructure.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchProductDocument;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.domain.RecordStatus;
import com.ecommerce.catalog.infrastructure.persistence.entity.BrandEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.CategoryEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSkuEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSpuEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.BrandMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.CategoryMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductSkuMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductSpuMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CatalogSearchProjectionReader {

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;

    public CatalogSearchProjectionReader(
            ProductSpuMapper spuMapper,
            ProductSkuMapper skuMapper,
            CategoryMapper categoryMapper,
            BrandMapper brandMapper) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
    }

    @Transactional(readOnly = true)
    public ProjectionState readState(Long productId) {
        ProductSpuEntity product = spuMapper.selectById(productId);
        if (product == null) {
            return new ProjectionState(1, Optional.empty());
        }
        long revision = product.getSearchRevision();
        if (!ProductStatus.ACTIVE.name().equals(product.getStatus())) {
            return new ProjectionState(revision, Optional.empty());
        }
        return new ProjectionState(revision, Optional.of(toDocuments(List.of(product)).get(0)));
    }

    @Transactional(readOnly = true)
    public List<SearchProductDocument> readActiveBatch(long afterId, int limit) {
        return toDocuments(spuMapper.selectActiveSearchBatch(afterId, limit));
    }

    private List<SearchProductDocument> toDocuments(List<ProductSpuEntity> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        Set<Long> productIds = products.stream().map(ProductSpuEntity::getId).collect(Collectors.toSet());
        Map<Long, CategoryEntity> categories = byId(
                categoryMapper.selectByIds(products.stream()
                        .map(ProductSpuEntity::getCategoryId)
                        .collect(Collectors.toSet())),
                CategoryEntity::getId);
        Map<Long, BrandEntity> brands = byId(
                brandMapper.selectByIds(products.stream()
                        .map(ProductSpuEntity::getBrandId)
                        .collect(Collectors.toSet())),
                BrandEntity::getId);
        Map<Long, List<ProductSkuEntity>> skus = skuMapper.selectList(
                        new LambdaQueryWrapper<ProductSkuEntity>()
                                .in(ProductSkuEntity::getSpuId, productIds)
                                .eq(ProductSkuEntity::getStatus, RecordStatus.ACTIVE.name())
                                .orderByAsc(ProductSkuEntity::getSpuId, ProductSkuEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(
                        ProductSkuEntity::getSpuId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return products.stream().map(product -> {
            CategoryEntity category = required(categories.get(product.getCategoryId()), "category", product.getId());
            BrandEntity brand = required(brands.get(product.getBrandId()), "brand", product.getId());
            List<ProductSkuEntity> productSkus = skus.getOrDefault(product.getId(), Collections.emptyList());
            return new SearchProductDocument(
                    product.getId(),
                    product.getSearchRevision(),
                    category.getId(),
                    category.getName(),
                    brand.getId(),
                    brand.getName(),
                    product.getTitle(),
                    product.getSubtitle(),
                    product.getDescription(),
                    productSkus.stream().map(ProductSkuEntity::getName).toList(),
                    productSkus.stream().map(ProductSkuEntity::getSpecJson).toList(),
                    product.getUpdatedAt());
        }).toList();
    }

    private <T> Map<Long, T> byId(List<T> values, Function<T, Long> idExtractor) {
        return values.stream().collect(Collectors.toMap(idExtractor, Function.identity()));
    }

    private <T> T required(T value, String type, Long productId) {
        if (value == null) {
            throw new IllegalStateException("Search projection is missing " + type + " for product " + productId);
        }
        return value;
    }

    public record ProjectionState(long revision, Optional<SearchProductDocument> document) {
    }
}
