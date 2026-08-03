package com.ecommerce.payment.infrastructure.client;

import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.infrastructure.config.InternalClientProperties;
import com.ecommerce.payment.infrastructure.config.PaymentClientProperties;
import com.ecommerce.payment.infrastructure.resilience.PaymentTradeResilience;
import com.ecommerce.payment.infrastructure.resilience.RemoteDependencyFailure;
import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Component
public class HttpTradeClient implements TradePort {

    private static final ParameterizedTypeReference<ApiResponse<PaymentContextResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;
    private final PaymentTradeResilience resilience;

    @Autowired
    public HttpTradeClient(
            RestClient.Builder paymentRestClientBuilder,
            InternalClientProperties properties,
            PaymentTradeResilience resilience,
            PaymentClientProperties clientProperties) {
        this(paymentRestClientBuilder.baseUrl(clientProperties.tradeBaseUrl()).build(), properties, resilience);
    }

    HttpTradeClient(
            RestClient restClient,
            InternalClientProperties properties,
            PaymentTradeResilience resilience) {
        this.restClient = restClient;
        this.properties = properties;
        this.resilience = resilience;
    }

    @Override
    public PaymentContext getPaymentContext(String orderNo) {
        return resilience.execute(() -> requestPaymentContext(orderNo));
    }

    private PaymentContext requestPaymentContext(String orderNo) {
        try {
            ApiResponse<PaymentContextResponse> response = restClient.get()
                    .uri("/api/v1/trade/internal/orders/{orderNo}/payment-context", orderNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            PaymentContextResponse data = response.data();
            if (!Objects.equals(orderNo, data.orderNo())) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            return new PaymentContext(
                    data.orderNo(),
                    data.userId(),
                    data.reservationNo(),
                    data.paymentNo(),
                    data.status(),
                    data.totalAmount(),
                    data.paymentDeadline());
        } catch (RemoteDependencyFailure exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw RemoteDependencyFailure.forHttpStatus(exception.getStatusCode(), exception);
        } catch (ResourceAccessException exception) {
            throw RemoteDependencyFailure.transientFailure(exception);
        } catch (RestClientException exception) {
            throw RemoteDependencyFailure.invalidResponse(exception);
        } catch (RuntimeException exception) {
            throw RemoteDependencyFailure.invalidResponse(exception);
        }
    }

    private record PaymentContextResponse(
            String orderNo,
            Long userId,
            String reservationNo,
            String paymentNo,
            String status,
            BigDecimal totalAmount,
            Instant paymentDeadline
    ) {
    }
}
