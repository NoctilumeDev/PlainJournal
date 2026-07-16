package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpIdentityClient implements AddressPort {

    private static final ParameterizedTypeReference<ApiResponse<AddressResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;

    public HttpIdentityClient(RestClient.Builder tradeRestClientBuilder, InternalClientProperties properties) {
        this.restClient = tradeRestClientBuilder.baseUrl("http://identity-service").build();
        this.properties = properties;
    }

    @Override
    public AddressSnapshot getAddress(Long userId, Long addressId) {
        try {
            ApiResponse<AddressResponse> response = restClient.get()
                    .uri("/api/v1/identity/internal/users/{userId}/addresses/{addressId}", userId, addressId)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE);
            }
            AddressResponse address = response.data();
            return new AddressSnapshot(address.id(), address.recipientName(), address.phone(),
                    address.province(), address.provinceCode(), address.city(), address.cityCode(),
                    address.district(), address.districtCode(), address.detailAddress(), address.postalCode());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new TradeException(TradeError.ADDRESS_UNAVAILABLE, exception);
            }
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        } catch (TradeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
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
