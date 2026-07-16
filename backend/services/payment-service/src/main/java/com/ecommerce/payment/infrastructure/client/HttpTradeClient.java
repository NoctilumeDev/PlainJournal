package com.ecommerce.payment.infrastructure.client;

import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.infrastructure.config.InternalClientProperties;
import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class HttpTradeClient implements TradePort {

    private static final ParameterizedTypeReference<ApiResponse<PaymentContextResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;

    public HttpTradeClient(RestClient.Builder paymentRestClientBuilder, InternalClientProperties properties) {
        this.restClient = paymentRestClientBuilder.baseUrl("http://trade-service").build();
        this.properties = properties;
    }

    @Override
    public PaymentContext getPaymentContext(String orderNo) {
        try {
            ApiResponse<PaymentContextResponse> response = restClient.get()
                    .uri("/api/v1/trade/internal/orders/{orderNo}/payment-context", orderNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw unavailable();
            }
            PaymentContextResponse data = response.data();
            return new PaymentContext(data.orderNo(), data.userId(), data.reservationNo(), data.status(),
                    data.totalAmount(), data.paymentDeadline());
        } catch (PaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    private PaymentException unavailable() {
        return new PaymentException(PaymentError.REMOTE_DEPENDENCY_UNAVAILABLE);
    }

    private record PaymentContextResponse(
            String orderNo,
            Long userId,
            String reservationNo,
            String status,
            BigDecimal totalAmount,
            Instant paymentDeadline
    ) {
    }
}
