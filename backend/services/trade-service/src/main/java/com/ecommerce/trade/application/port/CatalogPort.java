package com.ecommerce.trade.application.port;

import java.math.BigDecimal;
import java.util.List;

public interface CatalogPort {

    ProductSnapshot getProduct(Long productId);

    record ProductSnapshot(
            Long id,
            String title,
            String status,
            List<SkuSnapshot> skus,
            List<MediaSnapshot> media
    ) {
        public ProductSnapshot {
            skus = List.copyOf(skus);
            media = List.copyOf(media);
        }
    }

    record SkuSnapshot(
            Long id,
            String skuCode,
            String name,
            String specJson,
            BigDecimal salePrice,
            String status
    ) {
    }

    record MediaSnapshot(Long skuId, String objectKey, int sortOrder) {
    }
}
