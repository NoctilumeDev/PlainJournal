package com.ecommerce.catalog.application.service;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.CatalogModels.ProductSummary;
import com.ecommerce.catalog.application.model.SearchModels.ProductSearchPage;
import com.ecommerce.catalog.application.port.ProductSearchIndex;
import com.ecommerce.catalog.application.port.ProductSearchIndex.SearchResult;
import com.ecommerce.catalog.infrastructure.search.CatalogSearchProperties;
import com.ecommerce.catalog.infrastructure.search.SearchIndexUnavailableException;
import com.ecommerce.platform.common.api.PageResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CatalogProductSearchService {

    private static final long MAX_RESULT_WINDOW = 10_000;

    private final CatalogService catalogService;
    private final ProductSearchIndex index;
    private final CatalogSearchProperties properties;
    private final Counter indexRequests;
    private final Counter fallbackRequests;

    public CatalogProductSearchService(
            CatalogService catalogService,
            ProductSearchIndex index,
            CatalogSearchProperties properties,
            MeterRegistry registry) {
        this.catalogService = catalogService;
        this.index = index;
        this.properties = properties;
        this.indexRequests = Counter.builder("ecommerce.catalog.search.requests")
                .tag("source", "opensearch")
                .register(registry);
        this.fallbackRequests = Counter.builder("ecommerce.catalog.search.requests")
                .tag("source", "mysql_fallback")
                .register(registry);
    }

    public ProductSearchPage search(
            String query,
            long page,
            long size,
            Long categoryId) {
        String normalized = query.trim();
        int from = searchOffset(page, size);
        if (!properties.enabled()) {
            return fallback(normalized, page, size, categoryId);
        }
        try {
            SearchResult result = index.search(normalized, categoryId, from, Math.toIntExact(size));
            List<ProductSummary> authoritative = catalogService.listActiveProductSummaries(result.productIds());
            indexRequests.increment();
            return new ProductSearchPage(
                    authoritative,
                    page,
                    size,
                    result.total(),
                    "OPENSEARCH",
                    false);
        } catch (SearchIndexUnavailableException exception) {
            if (!properties.mysqlFallbackEnabled()) {
                throw new CatalogException(CatalogError.SEARCH_INDEX_UNAVAILABLE, exception);
            }
            return fallback(normalized, page, size, categoryId);
        }
    }

    private int searchOffset(long page, long size) {
        try {
            long offset = Math.multiplyExact(page - 1, size);
            long endExclusive = Math.addExact(offset, size);
            if (endExclusive > MAX_RESULT_WINDOW) {
                throw new IllegalArgumentException(
                        "Search result window cannot exceed " + MAX_RESULT_WINDOW + " items");
            }
            return Math.toIntExact(offset);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Search result window is too large", exception);
        }
    }

    private ProductSearchPage fallback(
            String query,
            long page,
            long size,
            Long categoryId) {
        if (!properties.mysqlFallbackEnabled()) {
            throw new CatalogException(CatalogError.SEARCH_INDEX_UNAVAILABLE);
        }
        PageResponse<ProductSummary> result = catalogService.listProducts(page, size, categoryId, query);
        fallbackRequests.increment();
        return new ProductSearchPage(
                result.items(),
                page,
                size,
                result.total(),
                "MYSQL_FALLBACK",
                true);
    }
}
