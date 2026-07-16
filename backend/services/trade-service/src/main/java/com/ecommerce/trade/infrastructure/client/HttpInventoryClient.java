package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

@Component
public class HttpInventoryClient implements InventoryPort {

    private static final ParameterizedTypeReference<ApiResponse<WarehouseResponse>> WAREHOUSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<ReservationResponse>> RESERVATION_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalClientProperties properties;

    public HttpInventoryClient(RestClient.Builder tradeRestClientBuilder, InternalClientProperties properties) {
        this.restClient = tradeRestClientBuilder.baseUrl("http://inventory-service").build();
        this.properties = properties;
    }

    @Override
    public WarehouseSnapshot getWarehouse(String code) {
        try {
            ApiResponse<WarehouseResponse> response = restClient.get()
                    .uri("/api/v1/inventory/internal/warehouses/{code}", code)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(WAREHOUSE_TYPE);
            if (response == null || response.data() == null) {
                throw unavailable();
            }
            return new WarehouseSnapshot(response.data().id(), response.data().code(), response.data().status());
        } catch (TradeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    @Override
    public ReservationSnapshot reserve(ReservationCommand command) {
        ReservationRequest body = new ReservationRequest(
                command.reservationNo(), command.orderNo(), command.warehouseId(), command.expiresAt(),
                command.items().stream().map(item -> new ReservationLineRequest(item.skuId(), item.quantity())).toList());
        try {
            ApiResponse<ReservationResponse> response = restClient.post()
                    .uri("/api/v1/inventory/internal/reservations")
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .body(body).retrieve().body(RESERVATION_TYPE);
            return reservation(response);
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    @Override
    public ReservationSnapshot getReservation(String reservationNo) {
        try {
            ApiResponse<ReservationResponse> response = restClient.get()
                    .uri("/api/v1/inventory/internal/reservations/{reservationNo}", reservationNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESERVATION_TYPE);
            return reservation(response);
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    @Override
    public ReservationSnapshot release(String reservationNo) {
        try {
            ApiResponse<ReservationResponse> response = restClient.post()
                    .uri("/api/v1/inventory/internal/reservations/{reservationNo}/release", reservationNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESERVATION_TYPE);
            return reservation(response);
        } catch (RuntimeException exception) {
            throw new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE, exception);
        }
    }

    private ReservationSnapshot reservation(ApiResponse<ReservationResponse> response) {
        if (response == null || response.data() == null) {
            throw unavailable();
        }
        return new ReservationSnapshot(
                response.data().reservationNo(), response.data().status(), response.data().warehouseId());
    }

    private TradeException unavailable() {
        return new TradeException(TradeError.REMOTE_DEPENDENCY_UNAVAILABLE);
    }

    private record WarehouseResponse(Long id, String code, String status) {
    }

    private record ReservationRequest(
            String reservationNo,
            String orderNo,
            Long warehouseId,
            Instant expiresAt,
            List<ReservationLineRequest> items
    ) {
    }

    private record ReservationLineRequest(Long skuId, long quantity) {
    }

    private record ReservationResponse(String reservationNo, Long warehouseId, String status) {
    }
}
