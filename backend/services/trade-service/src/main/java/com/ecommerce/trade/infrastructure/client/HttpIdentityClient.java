package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.resilience.RemoteDependencyFailure;
import com.ecommerce.trade.infrastructure.resilience.TradeSynchronousBoundaryResilience;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

@Component
public class HttpIdentityClient implements AddressPort {

    private static final ParameterizedTypeReference<ApiResponse<AddressResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;
    private final TradeSynchronousBoundaryResilience resilience;

    @Autowired
    public HttpIdentityClient(
            RestClient.Builder tradeRestClientBuilder,
            InternalClientProperties properties,
            RemoteClientProperties clientProperties,
            TradeSynchronousBoundaryResilience resilience) {
        this(
                tradeRestClientBuilder.baseUrl(clientProperties.identityBaseUrl()).build(),
                properties,
                resilience);
    }

    HttpIdentityClient(
            RestClient restClient,
            InternalClientProperties properties,
            TradeSynchronousBoundaryResilience resilience) {
        this.restClient = restClient;
        this.properties = properties;
        this.resilience = resilience;
    }

    @Override
    public AddressSnapshot getAddress(Long userId, Long addressId) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.IDENTITY_QUERY,
                () -> requestAddress(userId, addressId));
    }

    private AddressSnapshot requestAddress(Long userId, Long addressId) {
        try {
            ApiResponse<AddressResponse> response = restClient.get()
                    .uri("/api/v1/identity/internal/users/{userId}/addresses/{addressId}", userId, addressId)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            AddressResponse address = response.data();
            if (!Objects.equals(addressId, address.id())) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            return new AddressSnapshot(address.id(), address.recipientName(), address.phone(),
                    address.province(), address.provinceCode(), address.city(), address.cityCode(),
                    address.district(), address.districtCode(), address.detailAddress(), address.postalCode());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new TradeException(TradeError.ADDRESS_UNAVAILABLE, exception);
            }
            throw RemoteDependencyFailure.forHttpStatus(exception.getStatusCode(), exception);
        } catch (TradeException exception) {
            throw exception;
        } catch (RemoteDependencyFailure exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw RemoteDependencyFailure.transientFailure(exception);
        } catch (RestClientException exception) {
            throw RemoteDependencyFailure.invalidResponse(exception);
        } catch (RuntimeException exception) {
            throw RemoteDependencyFailure.invalidResponse(exception);
        }
    }

    private record AddressResponse(
            Long id,
            String recipientName,
            String phone,
            String province,
            String provinceCode,
            String city,
            String cityCode,
            String district,
            String districtCode,
            String detailAddress,
            String postalCode
    ) {
    }
}
