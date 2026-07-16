package com.ecommerce.catalog.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.CatalogModels.BrandView;
import com.ecommerce.catalog.application.model.CatalogModels.CategoryView;
import com.ecommerce.catalog.application.model.CatalogModels.CreateProductCommand;
import com.ecommerce.catalog.application.model.CatalogModels.CreateSkuCommand;
import com.ecommerce.catalog.application.model.CatalogModels.MediaView;
import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.model.CatalogModels.ProductSummary;
import com.ecommerce.catalog.application.model.CatalogModels.SkuView;
import com.ecommerce.catalog.application.model.CatalogModels.UpdateProductCommand;
import com.ecommerce.catalog.application.model.CatalogModels.UpdateSkuCommand;
import com.ecommerce.catalog.application.model.CatalogModels.UploadIntent;
import com.ecommerce.catalog.application.port.ObjectStorage;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.domain.RecordStatus;
import com.ecommerce.catalog.infrastructure.persistence.entity.BrandEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.CategoryEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductMediaEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSkuEntity;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSpuEntity;
import com.ecommerce.catalog.infrastructure.persistence.mapper.BrandMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.CategoryMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductMediaMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductSkuMapper;
import com.ecommerce.catalog.infrastructure.persistence.mapper.ProductSpuMapper;
import com.ecommerce.catalog.infrastructure.storage.MediaStorageProperties;
import com.ecommerce.platform.common.api.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductMediaMapper mediaMapper;
    private final ObjectStorage objectStorage;
    private final MediaStorageProperties mediaProperties;
    private final Clock clock;

    public CatalogService(
            CategoryMapper categoryMapper,
            BrandMapper brandMapper,
            ProductSpuMapper spuMapper,
            ProductSkuMapper skuMapper,
            ProductMediaMapper mediaMapper,
            ObjectStorage objectStorage,
            MediaStorageProperties mediaProperties,
            Clock clock) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.mediaMapper = mediaMapper;
        this.objectStorage = objectStorage;
        this.mediaProperties = mediaProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                        .eq(CategoryEntity::getStatus, RecordStatus.ACTIVE.name())
                        .orderByAsc(CategoryEntity::getSortOrder, CategoryEntity::getName))
                .stream()
                .map(this::categoryView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BrandView> listBrands() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandEntity>()
                        .eq(BrandEntity::getStatus, RecordStatus.ACTIVE.name())
                        .orderByAsc(BrandEntity::getName))
                .stream()
                .map(this::brandView)
                .toList();
    }

    @Transactional
    public CategoryView createCategory(Long parentId, String name, String slug, int sortOrder) {
        if (parentId != null) {
            requireActiveCategory(parentId);
        }
        Instant now = clock.instant();
        CategoryEntity entity = new CategoryEntity();
        entity.setParentId(parentId);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setStatus(RecordStatus.ACTIVE.name());
        entity.setSortOrder(sortOrder);
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        categoryMapper.insert(entity);
        return categoryView(entity);
    }

    @Transactional
    public BrandView createBrand(String name, String slug) {
        Instant now = clock.instant();
        BrandEntity entity = new BrandEntity();
        entity.setName(name);
        entity.setSlug(slug);
        entity.setStatus(RecordStatus.ACTIVE.name());
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        brandMapper.insert(entity);
        return brandView(entity);
    }

    @Transactional
    public ProductDetail createProduct(CreateProductCommand command) {
        CategoryEntity category = requireActiveCategory(command.categoryId());
        BrandEntity brand = requireActiveBrand(command.brandId());
        validateSkuCommands(command.skus());

        Instant now = clock.instant();
        ProductSpuEntity spu = new ProductSpuEntity();
        spu.setCategoryId(command.categoryId());
        spu.setBrandId(command.brandId());
        spu.setTitle(command.title());
        spu.setSubtitle(command.subtitle());
        spu.setDescription(command.description());
        spu.setStatus(ProductStatus.DRAFT.name());
        spu.setVersion(0);
        spu.setCreatedAt(now);
        spu.setUpdatedAt(now);
        spuMapper.insert(spu);

        List<ProductSkuEntity> skus = new ArrayList<>(command.skus().size());
        for (CreateSkuCommand skuCommand : command.skus()) {
            validatePrices(skuCommand.salePrice(), skuCommand.marketPrice());
            ProductSkuEntity sku = new ProductSkuEntity();
            sku.setSpuId(spu.getId());
            sku.setSkuCode(skuCommand.skuCode());
            sku.setName(skuCommand.name());
            sku.setSpecJson(skuCommand.specJson());
            sku.setSalePrice(skuCommand.salePrice());
            sku.setMarketPrice(skuCommand.marketPrice());
            sku.setStatus(RecordStatus.ACTIVE.name());
            sku.setVersion(0);
            sku.setCreatedAt(now);
            sku.setUpdatedAt(now);
            skuMapper.insert(sku);
            skus.add(sku);
        }
        return productDetail(spu, category, brand, skus, List.of());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> listProducts(long page, long size, Long categoryId, String keyword) {
        LambdaQueryWrapper<ProductSpuEntity> query = new LambdaQueryWrapper<ProductSpuEntity>()
                .eq(ProductSpuEntity::getStatus, ProductStatus.ACTIVE.name())
                .eq(categoryId != null, ProductSpuEntity::getCategoryId, categoryId)
                .like(StringUtils.hasText(keyword), ProductSpuEntity::getTitle, keyword)
                .orderByDesc(ProductSpuEntity::getCreatedAt);
        Page<ProductSpuEntity> result = spuMapper.selectPage(Page.of(page, size), query);
        List<ProductSpuEntity> products = result.getRecords();
        if (products.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, result.getTotal());
        }

        Set<Long> productIds = ids(products, ProductSpuEntity::getId);
        Map<Long, CategoryEntity> categories = entitiesById(
                categoryMapper.selectByIds(ids(products, ProductSpuEntity::getCategoryId)), CategoryEntity::getId);
        Map<Long, BrandEntity> brands = entitiesById(
                brandMapper.selectByIds(ids(products, ProductSpuEntity::getBrandId)), BrandEntity::getId);
        Map<Long, List<ProductSkuEntity>> skusByProduct = skuMapper.selectList(
                        new LambdaQueryWrapper<ProductSkuEntity>()
                                .in(ProductSkuEntity::getSpuId, productIds)
                                .eq(ProductSkuEntity::getStatus, RecordStatus.ACTIVE.name()))
                .stream().collect(Collectors.groupingBy(ProductSkuEntity::getSpuId));
        Map<Long, List<ProductMediaEntity>> mediaByProduct = mediaMapper.selectList(
                        new LambdaQueryWrapper<ProductMediaEntity>()
                                .in(ProductMediaEntity::getSpuId, productIds)
                                .orderByAsc(ProductMediaEntity::getSortOrder, ProductMediaEntity::getId))
                .stream().collect(Collectors.groupingBy(ProductMediaEntity::getSpuId, LinkedHashMap::new, Collectors.toList()));

        List<ProductSummary> summaries = products.stream().map(product -> {
            List<ProductSkuEntity> skus = skusByProduct.getOrDefault(product.getId(), List.of());
            BigDecimal minimumPrice = skus.stream()
                    .map(ProductSkuEntity::getSalePrice)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            String coverUrl = mediaByProduct.getOrDefault(product.getId(), List.of()).stream()
                    .findFirst()
                    .map(ProductMediaEntity::getObjectKey)
                    .map(this::safeDownloadUrl)
                    .orElse(null);
            return new ProductSummary(
                    product.getId(),
                    product.getTitle(),
                    product.getSubtitle(),
                    categoryView(categories.get(product.getCategoryId())),
                    brandView(brands.get(product.getBrandId())),
                    minimumPrice,
                    coverUrl
            );
        }).toList();
        return new PageResponse<>(summaries, page, size, result.getTotal());
    }

    @Transactional(readOnly = true)
    public ProductDetail getProduct(Long productId) {
        ProductSpuEntity spu = spuMapper.selectById(productId);
        if (spu == null || !ProductStatus.ACTIVE.name().equals(spu.getStatus())) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return loadProductDetail(spu);
    }

    @Transactional
    public ProductDetail updateProduct(Long productId, UpdateProductCommand command) {
        ProductSpuEntity spu = requireProduct(productId);
        requireVersion(spu.getVersion(), command.expectedVersion());
        requireActiveCategory(command.categoryId());
        requireActiveBrand(command.brandId());
        spu.setCategoryId(command.categoryId());
        spu.setBrandId(command.brandId());
        spu.setTitle(command.title());
        spu.setSubtitle(command.subtitle());
        spu.setDescription(command.description());
        spu.setUpdatedAt(clock.instant());
        requireUpdated(spuMapper.updateById(spu));
        return loadProductDetail(spuMapper.selectById(productId));
    }

    @Transactional
    public ProductDetail publishProduct(Long productId, int expectedVersion) {
        ProductSpuEntity spu = requireProduct(productId);
        requireVersion(spu.getVersion(), expectedVersion);
        if (ProductStatus.ACTIVE.name().equals(spu.getStatus())) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        Long activeSkuCount = skuMapper.selectCount(new LambdaQueryWrapper<ProductSkuEntity>()
                .eq(ProductSkuEntity::getSpuId, productId)
                .eq(ProductSkuEntity::getStatus, RecordStatus.ACTIVE.name()));
        if (activeSkuCount == 0) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        spu.setStatus(ProductStatus.ACTIVE.name());
        spu.setUpdatedAt(clock.instant());
        requireUpdated(spuMapper.updateById(spu));
        return loadProductDetail(spuMapper.selectById(productId));
    }

    @Transactional
    public ProductDetail unpublishProduct(Long productId, int expectedVersion) {
        ProductSpuEntity spu = requireProduct(productId);
        requireVersion(spu.getVersion(), expectedVersion);
        if (!ProductStatus.ACTIVE.name().equals(spu.getStatus())) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        spu.setStatus(ProductStatus.INACTIVE.name());
        spu.setUpdatedAt(clock.instant());
        requireUpdated(spuMapper.updateById(spu));
        return loadProductDetail(spuMapper.selectById(productId));
    }

    @Transactional
    public SkuView updateSku(Long productId, Long skuId, UpdateSkuCommand command) {
        requireProduct(productId);
        validatePrices(command.salePrice(), command.marketPrice());
        RecordStatus status;
        try {
            status = RecordStatus.valueOf(command.status());
        } catch (IllegalArgumentException exception) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        ProductSkuEntity sku = skuMapper.selectById(skuId);
        if (sku == null || !productId.equals(sku.getSpuId())) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        requireVersion(sku.getVersion(), command.expectedVersion());
        sku.setName(command.name());
        sku.setSpecJson(command.specJson());
        sku.setSalePrice(command.salePrice());
        sku.setMarketPrice(command.marketPrice());
        sku.setStatus(status.name());
        sku.setUpdatedAt(clock.instant());
        requireUpdated(skuMapper.updateById(sku));
        return skuView(skuMapper.selectById(skuId));
    }

    @Transactional(readOnly = true)
    public UploadIntent createUploadIntent(Long productId, String contentType, long sizeBytes) {
        requireProduct(productId);
        String normalizedType = normalizeMediaType(contentType);
        validateMedia(normalizedType, sizeBytes);
        String objectKey = "products/%d/%s.%s".formatted(
                productId,
                UUID.randomUUID().toString().replace("-", ""),
                extensionFor(normalizedType));
        String uploadUrl = objectStorage.createUploadUrl(
                mediaProperties.bucket(), objectKey, mediaProperties.uploadExpiry());
        return new UploadIntent(objectKey, uploadUrl, mediaProperties.uploadExpiry().toSeconds());
    }

    @Transactional
    public MediaView confirmMedia(Long productId, Long skuId, String objectKey, int sortOrder) {
        requireProduct(productId);
        if (!objectKey.startsWith("products/" + productId + "/")) {
            throw new CatalogException(CatalogError.INVALID_MEDIA);
        }
        if (skuId != null) {
            ProductSkuEntity sku = skuMapper.selectById(skuId);
            if (sku == null || !productId.equals(sku.getSpuId())) {
                throw new CatalogException(CatalogError.INVALID_MEDIA);
            }
        }
        ObjectStorage.StoredObject storedObject = objectStorage.stat(mediaProperties.bucket(), objectKey);
        String contentType = normalizeMediaType(storedObject.contentType());
        validateMedia(contentType, storedObject.sizeBytes());

        ProductMediaEntity media = new ProductMediaEntity();
        media.setSpuId(productId);
        media.setSkuId(skuId);
        media.setObjectKey(objectKey);
        media.setMimeType(contentType);
        media.setSizeBytes(storedObject.sizeBytes());
        media.setSortOrder(sortOrder);
        media.setCreatedAt(clock.instant());
        mediaMapper.insert(media);
        return mediaView(media);
    }

    private ProductDetail loadProductDetail(ProductSpuEntity spu) {
        CategoryEntity category = categoryMapper.selectById(spu.getCategoryId());
        BrandEntity brand = brandMapper.selectById(spu.getBrandId());
        List<ProductSkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductSkuEntity>()
                .eq(ProductSkuEntity::getSpuId, spu.getId())
                .orderByAsc(ProductSkuEntity::getCreatedAt));
        List<ProductMediaEntity> media = mediaMapper.selectList(new LambdaQueryWrapper<ProductMediaEntity>()
                .eq(ProductMediaEntity::getSpuId, spu.getId())
                .orderByAsc(ProductMediaEntity::getSortOrder, ProductMediaEntity::getId));
        return productDetail(spu, category, brand, skus, media);
    }

    private ProductDetail productDetail(
            ProductSpuEntity spu,
            CategoryEntity category,
            BrandEntity brand,
            List<ProductSkuEntity> skus,
            List<ProductMediaEntity> media) {
        return new ProductDetail(
                spu.getId(),
                spu.getTitle(),
                spu.getSubtitle(),
                spu.getDescription(),
                spu.getStatus(),
                spu.getVersion(),
                categoryView(category),
                brandView(brand),
                skus.stream().map(this::skuView).toList(),
                media.stream().map(this::mediaView).toList()
        );
    }

    private CategoryEntity requireActiveCategory(Long id) {
        CategoryEntity category = categoryMapper.selectById(id);
        if (category == null || !RecordStatus.ACTIVE.name().equals(category.getStatus())) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return category;
    }

    private BrandEntity requireActiveBrand(Long id) {
        BrandEntity brand = brandMapper.selectById(id);
        if (brand == null || !RecordStatus.ACTIVE.name().equals(brand.getStatus())) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return brand;
    }

    private ProductSpuEntity requireProduct(Long id) {
        ProductSpuEntity product = spuMapper.selectById(id);
        if (product == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return product;
    }

    private void validateSkuCommands(List<CreateSkuCommand> skus) {
        if (skus.isEmpty()) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
        Set<String> codes = new HashSet<>();
        for (CreateSkuCommand sku : skus) {
            if (!codes.add(sku.skuCode())) {
                throw new CatalogException(CatalogError.DUPLICATE_RESOURCE);
            }
        }
    }

    private void validatePrices(BigDecimal salePrice, BigDecimal marketPrice) {
        if (salePrice == null || salePrice.signum() <= 0 ||
                (marketPrice != null && (marketPrice.signum() <= 0 || marketPrice.compareTo(salePrice) < 0))) {
            throw new CatalogException(CatalogError.INVALID_STATE);
        }
    }

    private void validateMedia(String contentType, long sizeBytes) {
        if (!ALLOWED_MEDIA_TYPES.contains(contentType) || sizeBytes <= 0 ||
                sizeBytes > mediaProperties.maximumSize().toBytes()) {
            throw new CatalogException(CatalogError.INVALID_MEDIA);
        }
    }

    private String normalizeMediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int separator = contentType.indexOf(';');
        String value = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new CatalogException(CatalogError.INVALID_MEDIA);
        };
    }

    private String safeDownloadUrl(String objectKey) {
        try {
            return objectStorage.createDownloadUrl(
                    mediaProperties.bucket(), objectKey, mediaProperties.downloadExpiry());
        } catch (RuntimeException exception) {
            log.warn("Product media URL signing failed; returning catalog data without media URL: objectKey={}", objectKey);
            return null;
        }
    }

    private void requireVersion(Integer actualVersion, int expectedVersion) {
        if (actualVersion == null || actualVersion != expectedVersion) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
    }

    private void requireUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new CatalogException(CatalogError.CONCURRENT_MODIFICATION);
        }
    }

    private CategoryView categoryView(CategoryEntity category) {
        if (category == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return new CategoryView(category.getId(), category.getParentId(), category.getName(),
                category.getSlug(), category.getSortOrder());
    }

    private BrandView brandView(BrandEntity brand) {
        if (brand == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return new BrandView(brand.getId(), brand.getName(), brand.getSlug());
    }

    private SkuView skuView(ProductSkuEntity sku) {
        return new SkuView(sku.getId(), sku.getSkuCode(), sku.getName(), sku.getSpecJson(),
                sku.getSalePrice(), sku.getMarketPrice(), sku.getStatus(), sku.getVersion());
    }

    private MediaView mediaView(ProductMediaEntity media) {
        return new MediaView(media.getId(), media.getSkuId(), media.getObjectKey(), media.getMimeType(),
                media.getSizeBytes(), media.getSortOrder(), safeDownloadUrl(media.getObjectKey()));
    }

    private <T> Set<Long> ids(List<T> entities, Function<T, Long> idExtractor) {
        return entities.stream().map(idExtractor).collect(Collectors.toSet());
    }

    private <T> Map<Long, T> entitiesById(List<T> entities, Function<T, Long> idExtractor) {
        Map<Long, T> result = new HashMap<>();
        for (T entity : entities) {
            result.put(idExtractor.apply(entity), entity);
        }
        return result;
    }
}
