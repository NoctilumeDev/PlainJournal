package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.resilience.MarketingPricingLockFailure;
import com.ecommerce.trade.infrastructure.resilience.TradeMarketingPricingLockResilience;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;
import java.util.Objects;

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
    private final TradeMarketingPricingLockResilience resilience;

    @Autowired
    public HttpMarketingClient(
            @Qualifier("tradeMarketingRestClientBuilder") RestClient.Builder tradeRestClientBuilder,
            InternalClientProperties properties,
            RemoteClientProperties clientProperties,
            ObjectMapper objectMapper,
            TradeMarketingPricingLockResilience resilience) {
        this(tradeRestClientBuilder.baseUrl(clientProperties.marketingBaseUrl()).build(),
                properties, objectMapper, resilience);
    }

    HttpMarketingClient(
            RestClient restClient,
            InternalClientProperties properties,
            ObjectMapper objectMapper,
            TradeMarketingPricingLockResilience resilience) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resilience = resilience;
    }

    @Override
    public PricingLock lockPricing(PricingCommand command) {
        return resilience.execute(() -> requestPricingLock(command));
    }

    private PricingLock requestPricingLock(PricingCommand command) {
        try {
            ApiResponse<PricingLock> response = restClient.post()
                    .uri("/api/v1/marketing/internal/pricing-locks")
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .body(command)
                    .retrieve().body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw MarketingPricingLockFailure.invalidResponse();
            }
            PricingLock lock = response.data();
            if (!Objects.equals(command.orderNo(), lock.orderNo())
                    || !Objects.equals(command.userId(), lock.userId())) {
                throw MarketingPricingLockFailure.invalidResponse();
            }
            return lock;
        } catch (RestClientResponseException exception) {
            if (isBusinessRejection(exception)) {
                throw new PricingRejectedException("Marketing rejected the selected benefits");
            }
            throw MarketingPricingLockFailure.forHttpStatus(exception.getStatusCode(), exception);
        } catch (MarketingPricingLockFailure exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw MarketingPricingLockFailure.transientFailure(exception);
        } catch (RestClientException exception) {
            throw MarketingPricingLockFailure.invalidResponse(exception);
        } catch (RuntimeException exception) {
            throw MarketingPricingLockFailure.invalidResponse(exception);
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
