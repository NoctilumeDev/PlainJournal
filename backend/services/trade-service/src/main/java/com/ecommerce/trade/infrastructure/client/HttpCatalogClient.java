package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.CatalogPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;

@Component
public class HttpCatalogClient implements CatalogPort {

    private static final ParameterizedTypeReference<ApiResponse<ProductResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public HttpCatalogClient(RestClient.Builder tradeRestClientBuilder) {
        this.restClient = tradeRestClientBuilder.baseUrl("http://catalog-service").build();
    }

    @Override
    public ProductSnapshot getProduct(Long productId) {
        try {
            ApiResponse<ProductResponse> response = restClient.get()
                    .uri("/api/v1/catalog/products/{productId}", productId)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE);
            }
            ProductResponse product = response.data();
            return new ProductSnapshot(
                    product.id(),
                    product.title(),
                    product.status(),
                    product.skus().stream().map(sku -> new SkuSnapshot(
                            sku.id(), sku.skuCode(), sku.name(), sku.specJson(), sku.salePrice(), sku.status())).toList(),
                    product.media().stream().map(media -> new MediaSnapshot(
                            media.skuId(), media.objectKey(), media.sortOrder())).toList());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE, exception);
            }
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        } catch (TradeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    private record ProductResponse(
            Long id,
            String title,
            String status,
            List<SkuResponse> skus,
            List<MediaResponse> media
    ) {
    }

    private record SkuResponse(
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

    private record MediaResponse(Long skuId, String objectKey, int sortOrder) {
    }
}
