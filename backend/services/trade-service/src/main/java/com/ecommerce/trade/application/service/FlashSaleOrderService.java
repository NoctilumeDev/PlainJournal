package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.FlashSaleAdmissionAcceptedCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.domain.FlashSaleOrderRequestStatus;
import com.ecommerce.trade.domain.OrderStatus;
import com.ecommerce.trade.domain.OutboxStatus;
import com.ecommerce.trade.infrastructure.messaging.FlashSaleConsumerProperties;
import com.ecommerce.trade.infrastructure.observability.FlashSaleQueueMetrics;
import com.ecommerce.trade.infrastructure.persistence.entity.FlashSaleOrderRequestEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.FlashSaleOrderRequestMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FlashSaleOrderService {

    private final FlashSaleOrderRequestMapper requestMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final TradeOrderService orderService;
    private final FlashSaleConsumerProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final FlashSaleQueueMetrics queueMetrics;
    private final TradeShardRouter shardRouter;
    private final String recoveryOwner = "trade-flash-sale-recovery-"
            + UUID.randomUUID().toString().replace("-", "");

    public FlashSaleOrderService(
            FlashSaleOrderRequestMapper requestMapper,
            ConsumedEventMapper consumedEventMapper,
            OutboxEventMapper outboxMapper,
            TradeOrderService orderService,
            FlashSaleConsumerProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            FlashSaleQueueMetrics queueMetrics,
            TradeShardRouter shardRouter) {
        this.requestMapper = requestMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.outboxMapper = outboxMapper;
        this.orderService = orderService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.queueMetrics = queueMetrics;
        this.shardRouter = shardRouter;
    }

    public void handle(FlashSaleAdmissionAcceptedCommand command) {
        if (!shardRouter.isRouted()) {
            shardRouter.runForUser(command.userId(), () -> handle(command));
            return;
        }
        if (claim(command)) {
            recover(command.requestToken());
        }
    }

    public void recover(String requestToken) {
        if (!shardRouter.isRouted()) {
            FlashSaleOrderRequestEntity located = requestMapper.selectByToken(requestToken);
            if (located == null) {
                return;
            }
            shardRouter.runForUser(located.getUserId(), () -> recover(requestToken));
            return;
        }
        Instant now = requestMapper.currentTime();
        if (requestMapper.claimRecovery(
                requestToken,
                recoveryOwner,
                now,
                now.plus(properties.recoveryLease())) != 1) {
            return;
        }
        try {
            process(requestToken);
        } finally {
            requestMapper.releaseRecoveryClaim(requestToken, recoveryOwner);
        }
    }

    public List<String> findRecoverableTokens() {
        return requestMapper.selectRecoverableTokens(
                requestMapper.currentTime(), properties.recoveryBatchSize());
    }

    private boolean claim(FlashSaleAdmissionAcceptedCommand command) {
        validate(command);
        Boolean claimed = transactionTemplate.execute(ignored -> {
            Instant now = requestMapper.currentTime();
            String payloadFingerprint = PayloadFingerprint.of(
                    command.requestToken(),
                    command.activityNo(),
                    command.userId(),
                    command.addressId(),
                    command.productId(),
                    command.skuId(),
                    command.salePrice() == null
                            ? null
                            : command.salePrice().stripTrailingZeros().toPlainString(),
                    command.acceptedAt(),
                    command.activityEndsAt());
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), properties.consumerGroup(),
                    command.userId(), payloadFingerprint, now) != 1) {
                Long storedOwner = consumedEventMapper.selectOwnerUserId(
                        command.eventId(), properties.consumerGroup());
                String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                        command.eventId(), properties.consumerGroup());
                if (!java.util.Objects.equals(storedOwner, command.userId())
                        || !PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                    throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
                }
                return false;
            }
            FlashSaleOrderRequestEntity candidate = new FlashSaleOrderRequestEntity();
            candidate.setId(IdWorker.getId());
            candidate.setRequestToken(command.requestToken());
            candidate.setAdmissionEventId(command.eventId());
            candidate.setRequestHash(requestHash(command));
            candidate.setActivityNo(command.activityNo());
            candidate.setUserId(command.userId());
            candidate.setAddressId(command.addressId());
            candidate.setProductId(command.productId());
            candidate.setSkuId(command.skuId());
            candidate.setSalePrice(command.salePrice().setScale(2, RoundingMode.UNNECESSARY));
            candidate.setStatus(FlashSaleOrderRequestStatus.PROCESSING.name());
            candidate.setAttempts(0);
            candidate.setNextAttemptAt(now);
            candidate.setVersion(0);
            candidate.setAcceptedAt(command.acceptedAt());
            candidate.setActivityEndsAt(command.activityEndsAt());
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            requestMapper.insertOrLockExisting(candidate);

            FlashSaleOrderRequestEntity stored = requestMapper.selectByTokenForUpdate(command.requestToken());
            if (stored == null || !stored.getRequestHash().equals(candidate.getRequestHash())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            return candidate.getId().equals(stored.getId())
                    || FlashSaleOrderRequestStatus.PROCESSING.name().equals(stored.getStatus());
        });
        return Boolean.TRUE.equals(claimed);
    }

    private void process(String requestToken) {
        FlashSaleOrderRequestEntity request = requestMapper.selectByToken(requestToken);
        if (request == null || !FlashSaleOrderRequestStatus.PROCESSING.name().equals(request.getStatus())) {
            return;
        }
        FlashSaleAdmissionAcceptedCommand command = command(request);
        try {
            OrderView order = orderService.createFlashSaleOrder(command);
            if (List.of(
                    OrderStatus.PENDING_PAYMENT.name(),
                    OrderStatus.PAID.name(),
                    OrderStatus.FULFILLING.name(),
                    OrderStatus.SHIPPED.name(),
                    OrderStatus.COMPLETED.name()).contains(order.status())) {
                completeSuccess(requestToken, order.orderNo());
            } else if (OrderStatus.CLOSED.name().equals(order.status())) {
                completeFailure(requestToken,
                        order.closeReason() == null ? "OUT_OF_STOCK" : order.closeReason());
            } else {
                scheduleRetry(requestToken, "Order remains in state " + order.status());
            }
        } catch (TradeException exception) {
            if (isRetryable(exception.error())) {
                scheduleRetry(requestToken, conciseError(exception));
            } else {
                completeFailure(requestToken, failureCode(exception.error()));
            }
        } catch (RuntimeException exception) {
            scheduleRetry(requestToken, conciseError(exception));
        }
    }

    private void completeSuccess(String requestToken, String orderNo) {
        complete(requestToken, FlashSaleOrderRequestStatus.ORDER_CREATED, orderNo, null);
    }

    private void completeFailure(String requestToken, String failureCode) {
        complete(requestToken, FlashSaleOrderRequestStatus.FAILED, null, failureCode);
    }

    private void complete(
            String requestToken,
            FlashSaleOrderRequestStatus target,
            String orderNo,
            String failureCode) {
        Boolean transitioned = transactionTemplate.execute(ignored -> {
            FlashSaleOrderRequestEntity request = requestMapper.selectByTokenForUpdate(requestToken);
            if (request == null) {
                throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
            }
            if (!FlashSaleOrderRequestStatus.PROCESSING.name().equals(request.getStatus())) {
                requireSameResult(request, target, orderNo, failureCode);
                return false;
            }
            Instant now = requestMapper.currentTime();
            request.setStatus(target.name());
            request.setOrderNo(orderNo);
            request.setFailureCode(failureCode);
            request.setCompletedAt(now);
            request.setNextAttemptAt(now);
            request.setLastError(null);
            request.setUpdatedAt(now);
            requireUpdated(requestMapper.updateById(request));
            appendResultEvent(request, now);
            return true;
        });
        if (Boolean.TRUE.equals(transitioned)) {
            queueMetrics.recordCompleted(target == FlashSaleOrderRequestStatus.ORDER_CREATED);
        }
    }

    private void scheduleRetry(String requestToken, String error) {
        transactionTemplate.executeWithoutResult(ignored -> {
            FlashSaleOrderRequestEntity request = requestMapper.selectByTokenForUpdate(requestToken);
            if (request == null
                    || !FlashSaleOrderRequestStatus.PROCESSING.name().equals(request.getStatus())) {
                return;
            }
            int attempts = request.getAttempts() + 1;
            Instant now = requestMapper.currentTime();
            request.setAttempts(attempts);
            request.setLastError(error.length() <= 500 ? error : error.substring(0, 500));
            if (attempts >= properties.maxAttempts()) {
                request.setStatus(FlashSaleOrderRequestStatus.NEEDS_ATTENTION.name());
                request.setFailureCode("PROCESSING_EXHAUSTED");
                request.setNextAttemptAt(now);
            } else {
                long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
                request.setNextAttemptAt(now.plusSeconds(delaySeconds));
            }
            request.setUpdatedAt(now);
            requireUpdated(requestMapper.updateById(request));
        });
    }

    private void appendResultEvent(FlashSaleOrderRequestEntity request, Instant now) {
        String eventType = FlashSaleOrderRequestStatus.ORDER_CREATED.name().equals(request.getStatus())
                ? "FlashSaleOrderSucceeded" : "FlashSaleOrderFailed";
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "FlashSaleOrderRequest");
        envelope.put("aggregateId", request.getRequestToken());
        envelope.put("aggregateVersion", request.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "trade-service");
        envelope.put("traceId", MDC.get("traceId"));
        envelope.put("payloadVersion", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestToken", request.getRequestToken());
        payload.put("activityNo", request.getActivityNo());
        payload.put("userId", request.getUserId());
        payload.put("orderNo", request.getOrderNo());
        payload.put("failureCode", request.getFailureCode());
        payload.put("completedAt", request.getCompletedAt());
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("FlashSaleOrderRequest");
        event.setAggregateId(request.getRequestToken());
        event.setAggregateVersion(request.getVersion());
        event.setDestinationTopic(properties.topic());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private FlashSaleAdmissionAcceptedCommand command(FlashSaleOrderRequestEntity request) {
        return new FlashSaleAdmissionAcceptedCommand(
                request.getAdmissionEventId(),
                request.getRequestToken(),
                request.getActivityNo(),
                request.getUserId(),
                request.getAddressId(),
                request.getProductId(),
                request.getSkuId(),
                request.getSalePrice(),
                request.getAcceptedAt(),
                request.getActivityEndsAt());
    }

    private void validate(FlashSaleAdmissionAcceptedCommand command) {
        if (command == null
                || command.eventId() == null || command.eventId().isBlank()
                || command.requestToken() == null || command.requestToken().isBlank()
                || command.activityNo() == null || command.activityNo().isBlank()
                || command.userId() == null || command.userId() <= 0
                || command.addressId() == null || command.addressId() <= 0
                || command.productId() == null || command.productId() <= 0
                || command.skuId() == null || command.skuId() <= 0
                || command.salePrice() == null || command.salePrice().signum() <= 0
                || command.acceptedAt() == null || command.activityEndsAt() == null
                || command.acceptedAt().isAfter(command.activityEndsAt())) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
        if (command.salePrice().stripTrailingZeros().scale() > 2) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private String requestHash(FlashSaleAdmissionAcceptedCommand command) {
        String canonical = String.join("|",
                command.requestToken(),
                command.activityNo(),
                command.userId().toString(),
                command.addressId().toString(),
                command.productId().toString(),
                command.skuId().toString(),
                command.salePrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                command.acceptedAt().toString(),
                command.activityEndsAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isRetryable(TradeError error) {
        return error == TradeError.REMOTE_DEPENDENCY_UNAVAILABLE
                || error == TradeError.CONCURRENT_MODIFICATION;
    }

    private String failureCode(TradeError error) {
        return switch (error) {
            case PRODUCT_UNAVAILABLE -> "PRODUCT_UNAVAILABLE";
            case RESOURCE_NOT_FOUND -> "ADDRESS_OR_PRODUCT_NOT_FOUND";
            case IDEMPOTENCY_CONFLICT -> "INVALID_ADMISSION_EVENT";
            case INVALID_STATE -> "ORDER_STATE_REJECTED";
            default -> "ORDER_CREATION_REJECTED";
        };
    }

    private void requireSameResult(
            FlashSaleOrderRequestEntity request,
            FlashSaleOrderRequestStatus target,
            String orderNo,
            String failureCode) {
        boolean sameSuccess = target == FlashSaleOrderRequestStatus.ORDER_CREATED
                && FlashSaleOrderRequestStatus.ORDER_CREATED.name().equals(request.getStatus())
                && request.getOrderNo().equals(orderNo);
        boolean sameFailure = target == FlashSaleOrderRequestStatus.FAILED
                && FlashSaleOrderRequestStatus.FAILED.name().equals(request.getStatus())
                && request.getFailureCode().equals(failureCode);
        if (!sameSuccess && !sameFailure) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize flash-sale result event", exception);
        }
    }

    private String conciseError(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
        }
    }
}
