package com.ecommerce.catalog.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ProductSearchIndex {

    SearchResult search(String query, Long categoryId, int from, int size);

    void upsert(SearchProductDocument document);

    void delete(Long productId, long revision);

    void createIndex(String indexName);

    void deleteIndex(String indexName);

    void bulkIndex(String indexName, List<SearchProductDocument> documents);

    void replaceAlias(String targetIndex);

    boolean aliasTargets(String indexName);

    void deleteOwnedIndicesExcept(String retainedIndex);

    Map<Long, Long> scanVersions(int limit);

    record SearchProductDocument(
            Long productId,
            long revision,
            Long categoryId,
            String categoryName,
            Long brandId,
            String brandName,
            String title,
            String subtitle,
            String description,
            List<String> skuNames,
            List<String> skuSpecs,
            Instant updatedAt
    ) {
        public SearchProductDocument {
            skuNames = List.copyOf(skuNames);
            skuSpecs = List.copyOf(skuSpecs);
        }
    }

    record SearchResult(List<Long> productIds, long total) {
        public SearchResult {
            productIds = List.copyOf(productIds);
        }
    }
}
