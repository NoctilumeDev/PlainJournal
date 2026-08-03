package com.ecommerce.catalog.application.port;

import com.ecommerce.catalog.application.model.CatalogModels.ProductDetail;

import java.util.Optional;
import java.util.function.Supplier;

public interface ProductDetailCache {

    Optional<ProductDetail> get(Long productId, Supplier<Optional<ProductDetail>> loader);

    void invalidateAfterCommit(Long productId);

    void receiveInvalidation(Long productId);
}
