package com.ecommerce.trade.infrastructure.client;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.infrastructure.config.InternalClientProperties;
import com.ecommerce.trade.infrastructure.config.RemoteClientProperties;
import com.ecommerce.trade.infrastructure.resilience.RemoteDependencyFailure;
import com.ecommerce.trade.infrastructure.resilience.TradeSynchronousBoundaryResilience;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
    private final TradeSynchronousBoundaryResilience resilience;

    @Autowired
    public HttpInventoryClient(
            RestClient.Builder tradeRestClientBuilder,
            InternalClientProperties properties,
            RemoteClientProperties clientProperties,
            TradeSynchronousBoundaryResilience resilience) {
        this(
                tradeRestClientBuilder.baseUrl(clientProperties.inventoryBaseUrl()).build(),
                properties,
                resilience);
    }

    HttpInventoryClient(
            RestClient restClient,
            InternalClientProperties properties,
            TradeSynchronousBoundaryResilience resilience) {
        this.restClient = restClient;
        this.properties = properties;
        this.resilience = resilience;
    }

    @Override
    public WarehouseSnapshot getWarehouse(String code) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                () -> requestWarehouse(code));
    }

    private WarehouseSnapshot requestWarehouse(String code) {
        try {
            ApiResponse<WarehouseResponse> response = restClient.get()
                    .uri("/api/v1/inventory/internal/warehouses/{code}", code)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(WAREHOUSE_TYPE);
            if (response == null || response.data() == null) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            WarehouseResponse warehouse = response.data();
            if (!Objects.equals(code, warehouse.code())) {
                throw RemoteDependencyFailure.invalidResponse();
            }
            return new WarehouseSnapshot(warehouse.id(), warehouse.code(), warehouse.status());
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

    @Override
    public ReservationSnapshot reserve(ReservationCommand command) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                () -> requestReservation(command));
    }

    private ReservationSnapshot requestReservation(ReservationCommand command) {
        ReservationRequest body = new ReservationRequest(
                command.reservationNo(), command.orderNo(), command.warehouseId(), command.expiresAt(),
                command.items().stream().map(item -> new ReservationLineRequest(item.skuId(), item.quantity())).toList());
        try {
            ApiResponse<ReservationResponse> response = restClient.post()
                    .uri("/api/v1/inventory/internal/reservations")
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .body(body).retrieve().body(RESERVATION_TYPE);
            return reservation(response, command.reservationNo());
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

    @Override
    public ReservationSnapshot getReservation(String reservationNo) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_QUERY,
                () -> requestReservation(reservationNo));
    }

    private ReservationSnapshot requestReservation(String reservationNo) {
        try {
            ApiResponse<ReservationResponse> response = restClient.get()
                    .uri("/api/v1/inventory/internal/reservations/{reservationNo}", reservationNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESERVATION_TYPE);
            return reservation(response, reservationNo);
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

    @Override
    public ReservationSnapshot confirm(String reservationNo) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                () -> requestConfirm(reservationNo));
    }

    private ReservationSnapshot requestConfirm(String reservationNo) {
        try {
            ApiResponse<ReservationResponse> response = restClient.post()
                    .uri("/api/v1/inventory/internal/reservations/{reservationNo}/confirm", reservationNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESERVATION_TYPE);
            return reservation(response, reservationNo);
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

    @Override
    public ReservationSnapshot release(String reservationNo) {
        return resilience.execute(
                TradeSynchronousBoundaryResilience.Boundary.INVENTORY_COMMAND,
                () -> requestRelease(reservationNo));
    }

    private ReservationSnapshot requestRelease(String reservationNo) {
        try {
            ApiResponse<ReservationResponse> response = restClient.post()
                    .uri("/api/v1/inventory/internal/reservations/{reservationNo}/release", reservationNo)
                    .header("X-Internal-Service", properties.caller())
                    .header("X-Internal-Token", properties.token())
                    .retrieve().body(RESERVATION_TYPE);
            return reservation(response, reservationNo);
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

    private ReservationSnapshot reservation(
            ApiResponse<ReservationResponse> response,
            String expectedReservationNo) {
        if (response == null || response.data() == null) {
            throw RemoteDependencyFailure.invalidResponse();
        }
        if (!Objects.equals(expectedReservationNo, response.data().reservationNo())) {
            throw RemoteDependencyFailure.invalidResponse();
        }
        return new ReservationSnapshot(
                response.data().reservationNo(),
                response.data().orderNo(),
                response.data().status(),
                response.data().warehouseId(),
                response.data().expiresAt(),
                response.data().items().stream()
                        .map(item -> new ReservationLine(item.skuId(), item.quantity()))
                        .toList());
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

    private record ReservationResponse(
            String reservationNo,
            String orderNo,
            Long warehouseId,
            String status,
            Instant expiresAt,
            List<ReservationLineResponse> items
    ) {
    }

    private record ReservationLineResponse(Long skuId, long quantity) {
    }
}
