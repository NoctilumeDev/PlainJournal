package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;

@Component
public class HttpMarketingClient implements MarketingPort {

    private static final Set<String> BUSINESS_REJECTIONS = Set.of(
            "BENEFIT_NOT_ELIGIBLE", "DUPLICATE_BENEFIT_TYPE", "IDEMPOTENCY_CONFLICT",
            "INVALID_PRICING_REQUEST", "INVALID_STATE");
    private static final ParameterizedTypeReference<ApiResponse<PricingLock>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;
    private final ObjectMapper objectMapper;

    public HttpMarketingClient(
            RestClient.Builder tradeRestClientBuilder,
            InternalClientProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = tradeRestClientBuilder.baseUrl("http://marketing-service").build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public PricingLock lockPricing(PricingCommand command) {
        try {
            ApiResponse<PricingLock> response = restClient.post()
                    .uri("/api/v1/marketing/internal/pricing-locks")
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .body(command)
                    .retrieve().body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE);
            }
            return response.data();
        } catch (RestClientResponseException exception) {
            if (isBusinessRejection(exception)) {
                throw new PricingRejectedException("Marketing rejected the selected benefits");
            }
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        } catch (PricingRejectedException | TradeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    private boolean isBusinessRejection(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        if (!status.is4xxClientError()) {
            return false;
        }
        try {
            JsonNode response = objectMapper.readTree(exception.getResponseBodyAsByteArray());
            return BUSINESS_REJECTIONS.contains(response.path("code").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
