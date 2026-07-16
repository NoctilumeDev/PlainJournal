package com.ecommerce.fulfillment.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.fulfillment.application.exception.FulfillmentError;
import com.ecommerce.fulfillment.application.exception.FulfillmentException;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedItem;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ReturnItemView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ReturnReceiptView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.SubmitReturnShipmentCommand;
import com.ecommerce.fulfillment.domain.OutboxStatus;
import com.ecommerce.fulfillment.domain.ReturnReceiptStatus;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReturnItemEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReturnReceiptEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.ReturnStatusHistoryEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ReturnItemMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ReturnReceiptMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ReturnStatusHistoryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReturnReceiptService {

    public static final String AFTER_SALE_APPROVED_CONSUMER_GROUP = "fulfillment-after-sale-approved-v1";

    private final ReturnReceiptMapper receiptMapper;
    private final ReturnItemMapper itemMapper;
    private final ReturnStatusHistoryMapper historyMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReturnReceiptService(
            ReturnReceiptMapper receiptMapper,
            ReturnItemMapper itemMapper,
            ReturnStatusHistoryMapper historyMapper,
            ConsumedEventMapper consumedEventMapper,
            OutboxEventMapper outboxMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.receiptMapper = receiptMapper;
        this.itemMapper = itemMapper;
        this.historyMapper = historyMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ReturnReceiptView createFromAfterSaleApproved(AfterSaleApprovedCommand command) {
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(), AFTER_SALE_APPROVED_CONSUMER_GROUP, clock.instant()) != 1) {
            return view(requireByAfterSaleNo(command.afterSaleNo()));
        }
        ReturnReceiptEntity existing = receiptMapper.selectByAfterSaleNoForUpdate(command.afterSaleNo());
        if (existing != null) {
            requireSameApproval(existing, command);
            return view(existing);
        }
        validateApproval(command);

        Instant now = clock.instant();
        long id = IdWorker.getId();
        ReturnReceiptEntity receipt = new ReturnReceiptEntity();
        receipt.setId(id);
        receipt.setReturnReceiptNo("RET" + id);
        receipt.setAfterSaleNo(command.afterSaleNo());
        receipt.setOrderNo(command.orderNo());
        receipt.setUserId(command.userId());
        receipt.setWarehouseId(command.warehouseId());
        receipt.setReservationNo(command.reservationNo());
        receipt.setStatus(ReturnReceiptStatus.WAIT_SHIPMENT.name());
        receipt.setRefundAmount(command.refundAmount());
        receipt.setVersion(0);
        receipt.setCreatedAt(now);
        receipt.setUpdatedAt(now);
        receiptMapper.insert(receipt);
        for (AfterSaleApprovedItem source : command.items()) {
            ReturnItemEntity item = new ReturnItemEntity();
            item.setId(IdWorker.getId());
            item.setReturnReceiptId(id);
            item.setLineNo(source.lineNo());
            item.setSkuId(source.skuId());
            item.setQuantity(source.quantity());
            item.setRefundableAmount(source.refundableAmount());
            item.setCreatedAt(now);
            itemMapper.insert(item);
        }
        appendHistory(receipt, null, ReturnReceiptStatus.WAIT_SHIPMENT.name(),
                "CREATE_RETURN_RECEIPT", null, "SYSTEM", "trade-service", now);
        appendEvent(receipt, "ReturnReceiptCreated", now);
        return view(receipt);
    }

    @Transactional
    public ReturnReceiptView submitShipment(
            Long userId,
            String returnReceiptNo,
            SubmitReturnShipmentCommand command) {
        ReturnReceiptEntity receipt = requireLocked(returnReceiptNo);
        requireOwner(receipt, userId);
        if (ReturnReceiptStatus.RETURNING.name().equals(receipt.getStatus())
                && command.carrier().equals(receipt.getCarrier())
                && command.trackingNo().equals(receipt.getTrackingNo())) {
            return view(receipt);
        }
        requireStatus(receipt, ReturnReceiptStatus.WAIT_SHIPMENT);
        receipt.setCarrier(command.carrier());
        receipt.setTrackingNo(command.trackingNo());
        receipt.setShippedAt(clock.instant());
        try {
            transition(receipt, ReturnReceiptStatus.RETURNING, "SUBMIT_RETURN_SHIPMENT", null,
                    "CUSTOMER", userId.toString(), "ReturnShipmentSubmitted");
        } catch (DataIntegrityViolationException exception) {
            throw new FulfillmentException(FulfillmentError.DUPLICATE_TRACKING);
        }
        return view(receipt);
    }

    @Transactional
    public ReturnReceiptView receive(String returnReceiptNo, String operatorId) {
        ReturnReceiptEntity receipt = requireLocked(returnReceiptNo);
        if (List.of(ReturnReceiptStatus.RECEIVED.name(), ReturnReceiptStatus.INSPECTED.name())
                .contains(receipt.getStatus())) {
            return view(receipt);
        }
        requireStatus(receipt, ReturnReceiptStatus.RETURNING);
        receipt.setReceivedAt(clock.instant());
        transition(receipt, ReturnReceiptStatus.RECEIVED, "RECEIVE_RETURN", null,
                "WAREHOUSE", operatorId, "ReturnReceived");
        return view(receipt);
    }

    @Transactional
    public ReturnReceiptView inspect(String returnReceiptNo, String remark, String operatorId) {
        ReturnReceiptEntity receipt = requireLocked(returnReceiptNo);
        if (ReturnReceiptStatus.INSPECTED.name().equals(receipt.getStatus())) {
            return view(receipt);
        }
        requireStatus(receipt, ReturnReceiptStatus.RECEIVED);
        receipt.setInspectionRemark(remark);
        receipt.setInspectedAt(clock.instant());
        transition(receipt, ReturnReceiptStatus.INSPECTED, "INSPECT_RETURN", remark,
                "WAREHOUSE", operatorId, "ReturnInspected");
        return view(receipt);
    }

    public ReturnReceiptView getForUser(Long userId, String returnReceiptNo) {
        ReturnReceiptEntity receipt = require(returnReceiptNo);
        requireOwner(receipt, userId);
        return view(receipt);
    }

    public List<ReturnReceiptView> listForUser(Long userId) {
        return receiptMapper.selectList(new LambdaQueryWrapper<ReturnReceiptEntity>()
                        .eq(ReturnReceiptEntity::getUserId, userId)
                        .orderByDesc(ReturnReceiptEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public ReturnReceiptView get(String returnReceiptNo) {
        return view(require(returnReceiptNo));
    }

    public List<ReturnReceiptView> list(String status) {
        LambdaQueryWrapper<ReturnReceiptEntity> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            try {
                query.eq(ReturnReceiptEntity::getStatus, ReturnReceiptStatus.valueOf(status).name());
            } catch (IllegalArgumentException exception) {
                throw new FulfillmentException(FulfillmentError.INVALID_STATE);
            }
        }
        query.orderByDesc(ReturnReceiptEntity::getCreatedAt);
        return receiptMapper.selectList(query).stream().map(this::view).toList();
    }

    private void validateApproval(AfterSaleApprovedCommand command) {
        if (command.refundAmount() == null || command.refundAmount().signum() < 0 || command.items().isEmpty()) {
            throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AfterSaleApprovedItem item : command.items()) {
            if (item.lineNo() <= 0 || item.skuId() == null || item.skuId() <= 0
                    || item.quantity() <= 0 || item.refundableAmount() == null
                    || item.refundableAmount().signum() < 0) {
                throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
            }
            total = total.add(item.refundableAmount());
        }
        if (total.compareTo(command.refundAmount()) != 0) {
            throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireSameApproval(ReturnReceiptEntity receipt, AfterSaleApprovedCommand command) {
        validateApproval(command);
        if (!receipt.getOrderNo().equals(command.orderNo())
                || !receipt.getUserId().equals(command.userId())
                || !receipt.getWarehouseId().equals(command.warehouseId())
                || !receipt.getReservationNo().equals(command.reservationNo())
                || receipt.getRefundAmount().compareTo(command.refundAmount()) != 0) {
            throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
        }
        List<ReturnItemEntity> storedItems = items(receipt.getId());
        List<AfterSaleApprovedItem> incomingItems = command.items().stream()
                .sorted(java.util.Comparator.comparingInt(AfterSaleApprovedItem::lineNo))
                .toList();
        if (storedItems.size() != incomingItems.size()) {
            throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
        }
        for (int index = 0; index < storedItems.size(); index++) {
            ReturnItemEntity stored = storedItems.get(index);
            AfterSaleApprovedItem incoming = incomingItems.get(index);
            if (stored.getLineNo() != incoming.lineNo()
                    || !stored.getSkuId().equals(incoming.skuId())
                    || stored.getQuantity() != incoming.quantity()
                    || stored.getRefundableAmount().compareTo(incoming.refundableAmount()) != 0) {
                throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
            }
        }
    }

    private void transition(
            ReturnReceiptEntity receipt,
            ReturnReceiptStatus target,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            String eventType) {
        String from = receipt.getStatus();
        Instant now = clock.instant();
        receipt.setStatus(target.name());
        receipt.setVersion(receipt.getVersion() + 1);
        receipt.setUpdatedAt(now);
        requireUpdated(receiptMapper.updateById(receipt));
        appendHistory(receipt, from, target.name(), command, reason, operatorType, operatorId, now);
        appendEvent(receipt, eventType, now);
    }

    private void appendHistory(
            ReturnReceiptEntity receipt,
            String from,
            String to,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            Instant now) {
        ReturnStatusHistoryEntity history = new ReturnStatusHistoryEntity();
        history.setId(IdWorker.getId());
        history.setReturnReceiptId(receipt.getId());
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setCommand(command);
        history.setReason(reason);
        history.setOperatorType(operatorType);
        history.setOperatorId(operatorId);
        history.setCreatedAt(now);
        historyMapper.insert(history);
    }

    private void appendEvent(ReturnReceiptEntity receipt, String eventType, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnReceiptNo", receipt.getReturnReceiptNo());
        payload.put("afterSaleNo", receipt.getAfterSaleNo());
        payload.put("orderNo", receipt.getOrderNo());
        payload.put("userId", receipt.getUserId());
        payload.put("warehouseId", receipt.getWarehouseId());
        payload.put("reservationNo", receipt.getReservationNo());
        payload.put("refundAmount", receipt.getRefundAmount());
        payload.put("status", receipt.getStatus());
        payload.put("carrier", receipt.getCarrier());
        payload.put("trackingNo", receipt.getTrackingNo());
        if ("ReturnInspected".equals(eventType)) {
            payload.put("items", items(receipt.getId()).stream().map(item -> {
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
        envelope.put("aggregateType", "ReturnReceipt");
        envelope.put("aggregateId", receipt.getReturnReceiptNo());
        envelope.put("aggregateVersion", receipt.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "fulfillment-service");
        envelope.put("traceId", MDC.get("traceId"));
        envelope.put("payloadVersion", 1);
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("ReturnReceipt");
        event.setAggregateId(receipt.getReturnReceiptNo());
        event.setAggregateVersion(receipt.getVersion());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private ReturnReceiptEntity requireLocked(String returnReceiptNo) {
        ReturnReceiptEntity receipt = receiptMapper.selectByReceiptNoForUpdate(returnReceiptNo);
        if (receipt == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return receipt;
    }

    private ReturnReceiptEntity require(String returnReceiptNo) {
        ReturnReceiptEntity receipt = receiptMapper.selectOne(new LambdaQueryWrapper<ReturnReceiptEntity>()
                .eq(ReturnReceiptEntity::getReturnReceiptNo, returnReceiptNo));
        if (receipt == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return receipt;
    }

    private ReturnReceiptEntity requireByAfterSaleNo(String afterSaleNo) {
        ReturnReceiptEntity receipt = receiptMapper.selectOne(new LambdaQueryWrapper<ReturnReceiptEntity>()
                .eq(ReturnReceiptEntity::getAfterSaleNo, afterSaleNo));
        if (receipt == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return receipt;
    }

    private List<ReturnItemEntity> items(Long receiptId) {
        return itemMapper.selectList(new LambdaQueryWrapper<ReturnItemEntity>()
                .eq(ReturnItemEntity::getReturnReceiptId, receiptId)
                .orderByAsc(ReturnItemEntity::getLineNo));
    }

    private ReturnReceiptView view(ReturnReceiptEntity receipt) {
        List<ReturnItemView> itemViews = items(receipt.getId()).stream()
                .map(item -> new ReturnItemView(item.getLineNo(), item.getSkuId(), item.getQuantity(),
                        item.getRefundableAmount()))
                .toList();
        return new ReturnReceiptView(
                receipt.getReturnReceiptNo(), receipt.getAfterSaleNo(), receipt.getOrderNo(),
                receipt.getUserId(), receipt.getWarehouseId(), receipt.getReservationNo(), receipt.getStatus(),
                receipt.getRefundAmount(), receipt.getCarrier(), receipt.getTrackingNo(),
                receipt.getInspectionRemark(), itemViews, receipt.getVersion(), receipt.getCreatedAt(),
                receipt.getUpdatedAt(), receipt.getShippedAt(), receipt.getReceivedAt(), receipt.getInspectedAt());
    }

    private void requireOwner(ReturnReceiptEntity receipt, Long userId) {
        if (!Objects.equals(receipt.getUserId(), userId)) {
            throw new FulfillmentException(FulfillmentError.ACCESS_DENIED);
        }
    }

    private void requireStatus(ReturnReceiptEntity receipt, ReturnReceiptStatus status) {
        if (!status.name().equals(receipt.getStatus())) {
            throw new FulfillmentException(FulfillmentError.INVALID_STATE);
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new FulfillmentException(FulfillmentError.CONCURRENT_MODIFICATION);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize return receipt event", exception);
        }
    }
}
