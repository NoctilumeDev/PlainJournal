package com.ecommerce.catalog.infrastructure.cache;

import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;
import com.ecommerce.catalog.application.port.ProductDetailCache;

import java.util.Optional;
import java.util.function.Supplier;

public class BypassProductDetailCache implements ProductDetailCache {

    @Override
    public Optional<ProductDetail> get(
            Long productId,
            Supplier<Optional<ProductDetail>> loader) {
        return loader.get();
    }

    @Override
    public void invalidateAfterCommit(Long productId) {
        // No cache is active.
    }

    @Override
    public void receiveInvalidation(Long productId) {
        // No cache is active.
    }
}
