package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleFulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleItemView;
import com.ecommerce.trade.application.model.TradeModels.AfterSaleView;
import com.ecommerce.trade.application.model.TradeModels.ApplyAfterSaleCommand;
import com.ecommerce.trade.application.model.TradeModels.RefundEventCommand;
import com.ecommerce.trade.application.model.TradeModels.ReturnStockedCommand;
import com.ecommerce.trade.application.model.TradeModels.ReviewAfterSaleCommand;
import com.ecommerce.trade.domain.AfterSaleStatus;
import com.ecommerce.trade.domain.OrderStatus;
import com.ecommerce.trade.domain.OutboxStatus;
import com.ecommerce.trade.infrastructure.persistence.entity.AfterSaleHistoryEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.AfterSaleItemEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.AfterSaleOrderEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderItemEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.TradeOrderEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.AfterSaleHistoryMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.AfterSaleItemMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.AfterSaleOrderMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderItemMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.TradeOrderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AfterSaleService {

    public static final String FULFILLMENT_CONSUMER_GROUP = "trade-after-sale-fulfillment-v1";
    public static final String INVENTORY_CONSUMER_GROUP = "trade-after-sale-inventory-v1";
    public static final String REFUND_CONSUMER_GROUP = "trade-refund-events-v1";
    private static final String WHOLE_RETURN_REFUND = "WHOLE_RETURN_REFUND";

    private final AfterSaleOrderMapper afterSaleMapper;
    private final AfterSaleItemMapper itemMapper;
    private final AfterSaleHistoryMapper historyMapper;
    private final TradeOrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OutboxEventMapper outboxMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AfterSaleService(
            AfterSaleOrderMapper afterSaleMapper,
            AfterSaleItemMapper itemMapper,
            AfterSaleHistoryMapper historyMapper,
            TradeOrderMapper orderMapper,
            OrderItemMapper orderItemMapper,
            OutboxEventMapper outboxMapper,
            ConsumedEventMapper consumedEventMapper,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.afterSaleMapper = afterSaleMapper;
        this.itemMapper = itemMapper;
        this.historyMapper = historyMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.outboxMapper = outboxMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public AfterSaleView apply(ApplyAfterSaleCommand command) {
        String requestHash = sha256(command.orderNo() + "|" + command.reason());
        long id = IdWorker.getId();
        Instant now = clock.instant();
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            TradeOrderEntity order = orderMapper.selectForUpdate(command.orderNo());
            if (order == null) {
                throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
            }
            requireOwner(order.getUserId(), command.userId());
            if (!OrderStatus.COMPLETED.name().equals(order.getStatus()) || order.getWarehouseId() == null) {
                throw new TradeException(TradeError.INVALID_STATE);
            }

            AfterSaleOrderEntity candidate = new AfterSaleOrderEntity();
            candidate.setId(id);
            candidate.setAfterSaleNo("AS" + id);
            candidate.setOrderId(order.getId());
            candidate.setOrderNo(order.getOrderNo());
            candidate.setUserId(order.getUserId());
            candidate.setAfterSaleType(WHOLE_RETURN_REFUND);
            candidate.setStatus(AfterSaleStatus.APPLIED.name());
            candidate.setIdempotencyKey(command.idempotencyKey());
            candidate.setRequestHash(requestHash);
            candidate.setReason(command.reason());
            candidate.setRefundAmount(order.getTotalAmount());
            candidate.setWarehouseId(order.getWarehouseId());
            candidate.setReservationNo(order.getReservationNo());
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            int inserted = afterSaleMapper.insertIfAbsent(candidate);

            AfterSaleOrderEntity afterSale = afterSaleMapper.selectByIdempotencyForUpdate(
                    command.userId(), command.idempotencyKey());
            if (afterSale == null) {
                if (afterSaleMapper.selectByOrderForUpdate(order.getId()) != null) {
                    throw new TradeException(TradeError.AFTER_SALE_ALREADY_EXISTS);
                }
                throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
            }
            if (!constantEquals(afterSale.getRequestHash(), requestHash)) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            if (inserted == 1) {
                insertSnapshotItems(afterSale, orderItems(order.getId()), now);
                appendHistory(afterSale, null, AfterSaleStatus.APPLIED.name(), "APPLY_AFTER_SALE",
                        command.reason(), "CUSTOMER", command.userId().toString(), now);
                appendEvent(afterSale, "AfterSaleApplied", now);
            }
            return view(afterSale);
        }));
    }

    public AfterSaleView review(ReviewAfterSaleCommand command) {
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            AfterSaleOrderEntity afterSale = requireLocked(command.afterSaleNo());
            AfterSaleStatus target = command.approved() ? AfterSaleStatus.WAIT_RETURN : AfterSaleStatus.REJECTED;
            if (target.name().equals(afterSale.getStatus())) {
                return view(afterSale);
            }
            requireStatus(afterSale, AfterSaleStatus.APPLIED);
            afterSale.setReviewReason(command.reason());
            if (command.approved()) {
                afterSale.setApprovedAt(clock.instant());
            }
            transition(afterSale, target, command.approved() ? "APPROVE_AFTER_SALE" : "REJECT_AFTER_SALE",
                    command.reason(), "ADMIN", command.operatorId(),
                    command.approved() ? "AfterSaleApproved" : "AfterSaleRejected");
            return view(afterSale);
        }));
    }

    public AfterSaleView cancel(Long userId, String afterSaleNo) {
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            AfterSaleOrderEntity afterSale = requireLocked(afterSaleNo);
            requireOwner(afterSale.getUserId(), userId);
            if (AfterSaleStatus.CANCELED.name().equals(afterSale.getStatus())) {
                return view(afterSale);
            }
            requireStatus(afterSale, AfterSaleStatus.APPLIED);
            transition(afterSale, AfterSaleStatus.CANCELED, "CANCEL_AFTER_SALE", null,
                    "CUSTOMER", userId.toString(), "AfterSaleCanceled");
            return view(afterSale);
        }));
    }

    public AfterSaleView getForUser(Long userId, String afterSaleNo) {
        AfterSaleOrderEntity afterSale = require(afterSaleNo);
        requireOwner(afterSale.getUserId(), userId);
        return view(afterSale);
    }

    public List<AfterSaleView> listForUser(Long userId) {
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSaleOrderEntity>()
                        .eq(AfterSaleOrderEntity::getUserId, userId)
                        .orderByDesc(AfterSaleOrderEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public AfterSaleView get(String afterSaleNo) {
        return view(require(afterSaleNo));
    }

    public List<AfterSaleView> list(String status) {
        LambdaQueryWrapper<AfterSaleOrderEntity> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            try {
                query.eq(AfterSaleOrderEntity::getStatus, AfterSaleStatus.valueOf(status).name());
            } catch (IllegalArgumentException exception) {
                throw new TradeException(TradeError.INVALID_STATE);
            }
        }
        query.orderByDesc(AfterSaleOrderEntity::getCreatedAt);
        return afterSaleMapper.selectList(query).stream().map(this::view).toList();
    }

    public void applyFulfillmentEvent(AfterSaleFulfillmentEventCommand command) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), FULFILLMENT_CONSUMER_GROUP, clock.instant()) != 1) {
                return;
            }
            AfterSaleOrderEntity afterSale = requireLocked(command.afterSaleNo());
            requireEventIdentity(afterSale, command.orderNo(), command.userId());
            if (afterSale.getReturnReceiptNo() != null
                    && !afterSale.getReturnReceiptNo().equals(command.returnReceiptNo())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            afterSale.setReturnReceiptNo(command.returnReceiptNo());
            switch (command.eventType()) {
                case "ReturnShipmentSubmitted" -> applyReturnShipmentSubmitted(afterSale);
                case "ReturnReceived" -> applyReturnReceived(afterSale);
                default -> throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        });
    }

    public void applyReturnStocked(ReturnStockedCommand command) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), INVENTORY_CONSUMER_GROUP, clock.instant()) != 1) {
                return;
            }
            AfterSaleOrderEntity afterSale = requireLocked(command.afterSaleNo());
            requireEventIdentity(afterSale, command.orderNo(), command.userId());
            if (!afterSale.getWarehouseId().equals(command.warehouseId())
                    || (afterSale.getReturnReceiptNo() != null
                    && !afterSale.getReturnReceiptNo().equals(command.returnReceiptNo()))) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            afterSale.setReturnReceiptNo(command.returnReceiptNo());
            if (List.of(AfterSaleStatus.REFUNDING.name(), AfterSaleStatus.REFUND_FAILED.name(),
                    AfterSaleStatus.COMPLETED.name()).contains(afterSale.getStatus())) {
                return;
            }
            if (!List.of(AfterSaleStatus.WAIT_RETURN.name(), AfterSaleStatus.RETURNING.name(),
                    AfterSaleStatus.RECEIVED.name()).contains(afterSale.getStatus())) {
                throw new TradeException(TradeError.INVALID_STATE);
            }
            transition(afterSale, AfterSaleStatus.REFUNDING, "RETURN_STOCKED", null,
                    "SYSTEM", "inventory-service", "RefundRequested");
        });
    }

    public void applyRefundEvent(RefundEventCommand command) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), REFUND_CONSUMER_GROUP, clock.instant()) != 1) {
                return;
            }
            AfterSaleOrderEntity afterSale = requireLocked(command.afterSaleNo());
            requireEventIdentity(afterSale, command.orderNo(), command.userId());
            if (afterSale.getRefundAmount().compareTo(command.amount()) != 0
                    || (afterSale.getRefundNo() != null && !afterSale.getRefundNo().equals(command.refundNo()))) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            afterSale.setRefundNo(command.refundNo());
            if ("RefundSucceeded".equals(command.eventType())) {
                if (AfterSaleStatus.COMPLETED.name().equals(afterSale.getStatus())) {
                    return;
                }
                if (!List.of(AfterSaleStatus.REFUNDING.name(), AfterSaleStatus.REFUND_FAILED.name())
                        .contains(afterSale.getStatus())) {
                    throw new TradeException(TradeError.INVALID_STATE);
                }
                afterSale.setCompletedAt(clock.instant());
                transition(afterSale, AfterSaleStatus.COMPLETED, "REFUND_SUCCEEDED", null,
                        "SYSTEM", "payment-service", "AfterSaleCompleted");
            } else if ("RefundFailed".equals(command.eventType())) {
                if (AfterSaleStatus.REFUND_FAILED.name().equals(afterSale.getStatus())) {
                    return;
                }
                requireStatus(afterSale, AfterSaleStatus.REFUNDING);
                transition(afterSale, AfterSaleStatus.REFUND_FAILED, "REFUND_FAILED", null,
                        "SYSTEM", "payment-service", "AfterSaleRefundFailed");
            } else {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        });
    }

    private void applyReturnShipmentSubmitted(AfterSaleOrderEntity afterSale) {
        if (List.of(AfterSaleStatus.RETURNING.name(), AfterSaleStatus.RECEIVED.name(),
                AfterSaleStatus.REFUNDING.name(), AfterSaleStatus.REFUND_FAILED.name(),
                AfterSaleStatus.COMPLETED.name()).contains(afterSale.getStatus())) {
            return;
        }
        requireStatus(afterSale, AfterSaleStatus.WAIT_RETURN);
        transition(afterSale, AfterSaleStatus.RETURNING, "RETURN_SHIPMENT_SUBMITTED", null,
                "SYSTEM", "fulfillment-service", "AfterSaleReturning");
    }

    private void applyReturnReceived(AfterSaleOrderEntity afterSale) {
        if (List.of(AfterSaleStatus.RECEIVED.name(), AfterSaleStatus.REFUNDING.name(),
                AfterSaleStatus.REFUND_FAILED.name(), AfterSaleStatus.COMPLETED.name())
                .contains(afterSale.getStatus())) {
            return;
        }
        if (!List.of(AfterSaleStatus.WAIT_RETURN.name(), AfterSaleStatus.RETURNING.name())
                .contains(afterSale.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(afterSale, AfterSaleStatus.RECEIVED, "RETURN_RECEIVED", null,
                "SYSTEM", "fulfillment-service", "AfterSaleReturnReceived");
    }

    private void insertSnapshotItems(
            AfterSaleOrderEntity afterSale,
            List<OrderItemEntity> sourceItems,
            Instant now) {
        if (sourceItems.isEmpty()) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        BigDecimal refundableTotal = BigDecimal.ZERO;
        for (OrderItemEntity source : sourceItems) {
            AfterSaleItemEntity item = new AfterSaleItemEntity();
            item.setId(IdWorker.getId());
            item.setAfterSaleId(afterSale.getId());
            item.setOrderItemId(source.getId());
            item.setLineNo(source.getLineNo());
            item.setSkuId(source.getSkuId());
            item.setProductTitle(source.getProductTitle());
            item.setSkuName(source.getSkuName());
            item.setQuantity(source.getQuantity());
            item.setLineAmount(source.getLineAmount());
            item.setDiscountAmount(source.getDiscountAmount());
            item.setRefundableAmount(source.getPayableAmount());
            item.setCreatedAt(now);
            itemMapper.insert(item);
            refundableTotal = refundableTotal.add(source.getPayableAmount());
        }
        if (refundableTotal.compareTo(afterSale.getRefundAmount()) != 0) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void transition(
            AfterSaleOrderEntity afterSale,
            AfterSaleStatus target,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            String eventType) {
        String from = afterSale.getStatus();
        Instant now = clock.instant();
        afterSale.setStatus(target.name());
        afterSale.setUpdatedAt(now);
        requireUpdated(afterSaleMapper.updateById(afterSale));
        appendHistory(afterSale, from, target.name(), command, reason, operatorType, operatorId, now);
        if (eventType != null) {
            appendEvent(afterSale, eventType, now);
        }
    }

    private void appendHistory(
            AfterSaleOrderEntity afterSale,
            String from,
            String to,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            Instant now) {
        AfterSaleHistoryEntity history = new AfterSaleHistoryEntity();
        history.setId(IdWorker.getId());
        history.setAfterSaleId(afterSale.getId());
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setCommand(command);
        history.setReason(reason);
        history.setOperatorType(operatorType);
        history.setOperatorId(operatorId);
        history.setCreatedAt(now);
        historyMapper.insert(history);
    }

    private void appendEvent(AfterSaleOrderEntity afterSale, String eventType, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("afterSaleNo", afterSale.getAfterSaleNo());
        payload.put("orderNo", afterSale.getOrderNo());
        payload.put("userId", afterSale.getUserId());
        payload.put("afterSaleType", afterSale.getAfterSaleType());
        payload.put("status", afterSale.getStatus());
        payload.put("refundAmount", afterSale.getRefundAmount());
        payload.put("warehouseId", afterSale.getWarehouseId());
        payload.put("reservationNo", afterSale.getReservationNo());
        if (afterSale.getReturnReceiptNo() != null) {
            payload.put("returnReceiptNo", afterSale.getReturnReceiptNo());
        }
        if (afterSale.getRefundNo() != null) {
            payload.put("refundNo", afterSale.getRefundNo());
        }
        if ("AfterSaleApproved".equals(eventType)) {
            payload.put("items", items(afterSale.getId()).stream().map(item -> {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("lineNo", item.getLineNo());
                line.put("skuId", item.getSkuId());
                line.put("quantity", item.getQuantity());
                line.put("refundableAmount", item.getRefundableAmount());
                return line;
            }).toList());
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "AfterSaleOrder");
        envelope.put("aggregateId", afterSale.getAfterSaleNo());
        envelope.put("aggregateVersion", afterSale.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "trade-service");
        envelope.put("traceId", MDC.get("traceId"));
        envelope.put("payloadVersion", 1);
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("AfterSaleOrder");
        event.setAggregateId(afterSale.getAfterSaleNo());
        event.setAggregateVersion(afterSale.getVersion());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private AfterSaleOrderEntity require(String afterSaleNo) {
        AfterSaleOrderEntity afterSale = afterSaleMapper.selectOne(
                new LambdaQueryWrapper<AfterSaleOrderEntity>()
                        .eq(AfterSaleOrderEntity::getAfterSaleNo, afterSaleNo));
        if (afterSale == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return afterSale;
    }

    private AfterSaleOrderEntity requireLocked(String afterSaleNo) {
        AfterSaleOrderEntity afterSale = afterSaleMapper.selectForUpdate(afterSaleNo);
        if (afterSale == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return afterSale;
    }

    private List<OrderItemEntity> orderItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                .eq(OrderItemEntity::getOrderId, orderId)
                .orderByAsc(OrderItemEntity::getLineNo));
    }

    private List<AfterSaleItemEntity> items(Long afterSaleId) {
        return itemMapper.selectList(new LambdaQueryWrapper<AfterSaleItemEntity>()
                .eq(AfterSaleItemEntity::getAfterSaleId, afterSaleId)
                .orderByAsc(AfterSaleItemEntity::getLineNo));
    }

    private AfterSaleView view(AfterSaleOrderEntity afterSale) {
        List<AfterSaleItemView> itemViews = items(afterSale.getId()).stream()
                .map(item -> new AfterSaleItemView(
                        item.getLineNo(), item.getSkuId(), item.getProductTitle(), item.getSkuName(),
                        item.getQuantity(), item.getLineAmount(), item.getDiscountAmount(),
                        item.getRefundableAmount()))
                .toList();
        return new AfterSaleView(
                afterSale.getAfterSaleNo(), afterSale.getOrderNo(), afterSale.getUserId(),
                afterSale.getAfterSaleType(), afterSale.getStatus(), afterSale.getReason(),
                afterSale.getReviewReason(), afterSale.getRefundAmount(), afterSale.getReturnReceiptNo(),
                afterSale.getRefundNo(), itemViews, afterSale.getVersion(), afterSale.getCreatedAt(),
                afterSale.getUpdatedAt(), afterSale.getApprovedAt(), afterSale.getCompletedAt());
    }

    private void requireStatus(AfterSaleOrderEntity afterSale, AfterSaleStatus expected) {
        if (!expected.name().equals(afterSale.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
    }

    private void requireOwner(Long actualUserId, Long expectedUserId) {
        if (!actualUserId.equals(expectedUserId)) {
            throw new TradeException(TradeError.FORBIDDEN);
        }
    }

    private void requireEventIdentity(AfterSaleOrderEntity afterSale, String orderNo, Long userId) {
        if (!afterSale.getOrderNo().equals(orderNo) || !afterSale.getUserId().equals(userId)) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize after-sale event", exception);
        }
    }
}
