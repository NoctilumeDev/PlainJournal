package com.ecommerce.inventory.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedItem;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnStockView;
import com.ecommerce.inventory.domain.MovementType;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReservationEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReservationItemEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReturnEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.ecommerce.inventory.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReservationItemMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReservationMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReturnMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.StockMovementMapper;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReturnStockService {

    public static final String RETURN_INSPECTED_CONSUMER_GROUP = "inventory-return-inspected-v1";

    private final InventoryReturnMapper returnMapper;
    private final InventoryReservationMapper reservationMapper;
    private final InventoryReservationItemMapper reservationItemMapper;
    private final InventoryBalanceMapper balanceMapper;
    private final StockMovementMapper movementMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public ReturnStockService(
            InventoryReturnMapper returnMapper,
            InventoryReservationMapper reservationMapper,
            InventoryReservationItemMapper reservationItemMapper,
            InventoryBalanceMapper balanceMapper,
            StockMovementMapper movementMapper,
            ConsumedEventMapper consumedEventMapper,
            OutboxEventMapper outboxMapper,
            ObjectMapper objectMapper) {
        this.returnMapper = returnMapper;
        this.reservationMapper = reservationMapper;
        this.reservationItemMapper = reservationItemMapper;
        this.balanceMapper = balanceMapper;
        this.movementMapper = movementMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReturnStockView stock(ReturnInspectedCommand command) {
        List<ReturnInspectedItem> items = normalize(command.items());
        String requestHash = requestHash(command, items);
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(),
                RETURN_INSPECTED_CONSUMER_GROUP,
                requestHash,
                reservationMapper.currentTime()) != 1) {
            String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                    command.eventId(), RETURN_INSPECTED_CONSUMER_GROUP);
            if (!PayloadFingerprint.matches(storedFingerprint, requestHash)) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
            InventoryReturnEntity repeated = requireReturn(command.afterSaleNo());
            if (!PayloadFingerprint.matches(repeated.getRequestHash(), requestHash)) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
            return view(repeated);
        }
        Instant now = reservationMapper.currentTime();

        InventoryReturnEntity candidate = new InventoryReturnEntity();
        candidate.setId(IdWorker.getId());
        candidate.setAfterSaleNo(command.afterSaleNo());
        candidate.setReturnReceiptNo(command.returnReceiptNo());
        candidate.setOrderNo(command.orderNo());
        candidate.setUserId(command.userId());
        candidate.setWarehouseId(command.warehouseId());
        candidate.setReservationNo(command.reservationNo());
        candidate.setRequestHash(requestHash);
        candidate.setStatus("PENDING");
        candidate.setCreatedAt(now);
        returnMapper.insertOrLockExisting(candidate);

        InventoryReturnEntity inventoryReturn = returnMapper.selectByAfterSaleNoForUpdate(command.afterSaleNo());
        if (inventoryReturn == null) {
            throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
        }
        if (!inventoryReturn.getRequestHash().equals(requestHash)) {
            throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
        }
        if (!candidate.getId().equals(inventoryReturn.getId())) {
            if (!"STOCKED".equals(inventoryReturn.getStatus())) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
            return view(inventoryReturn);
        }

        InventoryReservationEntity reservation = reservationMapper.selectForUpdate(command.reservationNo());
        validateOriginalReservation(reservation, command, items);
        for (ReturnInspectedItem item : items) {
            if (balanceMapper.adjustOnHand(command.warehouseId(), item.skuId(), item.quantity(), now) != 1) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
            InventoryBalanceEntity balance = balanceMapper.selectForUpdate(command.warehouseId(), item.skuId());
            StockMovementEntity movement = new StockMovementEntity();
            movement.setMovementNo(command.afterSaleNo() + ":RETURN:" + item.skuId());
            movement.setWarehouseId(command.warehouseId());
            movement.setSkuId(item.skuId());
            movement.setReservationNo(command.reservationNo());
            movement.setMovementType(MovementType.RETURN.name());
            movement.setQuantityDelta(item.quantity());
            movement.setOnHandAfter(balance.getOnHand());
            movement.setReservedAfter(balance.getReserved());
            movement.setReason("Whole-order return inspected and stocked");
            movement.setCreatedAt(now);
            movementMapper.insert(movement);
        }

        inventoryReturn.setStatus("STOCKED");
        inventoryReturn.setStockedAt(now);
        requireUpdated(returnMapper.updateById(inventoryReturn));
        appendReturnStockedEvent(inventoryReturn, items, now);
        return view(inventoryReturn);
    }

    private void validateOriginalReservation(
            InventoryReservationEntity reservation,
            ReturnInspectedCommand command,
            List<ReturnInspectedItem> returnedItems) {
        if (reservation == null
                || !ReservationStatus.CONFIRMED.name().equals(reservation.getStatus())
                || !reservation.getOrderNo().equals(command.orderNo())
                || !reservation.getWarehouseId().equals(command.warehouseId())) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
        List<InventoryReservationItemEntity> original = reservationItemMapper.selectList(
                new LambdaQueryWrapper<InventoryReservationItemEntity>()
                        .eq(InventoryReservationItemEntity::getReservationId, reservation.getId())
                        .orderByAsc(InventoryReservationItemEntity::getSkuId));
        if (original.size() != returnedItems.size()) {
            throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
        }
        for (int index = 0; index < original.size(); index++) {
            InventoryReservationItemEntity expected = original.get(index);
            ReturnInspectedItem actual = returnedItems.get(index);
            if (!expected.getSkuId().equals(actual.skuId()) || expected.getQuantity() != actual.quantity()) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
        }
    }

    private List<ReturnInspectedItem> normalize(List<ReturnInspectedItem> input) {
        if (input == null || input.isEmpty()) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
        Map<Long, ReturnInspectedItem> unique = new LinkedHashMap<>();
        for (ReturnInspectedItem item : input) {
            if (item.lineNo() <= 0 || item.skuId() == null || item.skuId() <= 0 || item.quantity() <= 0
                    || unique.putIfAbsent(item.skuId(), item) != null) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
        }
        return new ArrayList<>(unique.values()).stream()
                .sorted(Comparator.comparing(ReturnInspectedItem::skuId))
                .toList();
    }

    private String requestHash(ReturnInspectedCommand command, List<ReturnInspectedItem> items) {
        String itemText = items.stream()
                .map(item -> item.lineNo() + ":" + item.skuId() + ":" + item.quantity())
                .collect(Collectors.joining(","));
        return sha256(String.join("|",
                command.afterSaleNo(), command.returnReceiptNo(), command.orderNo(),
                command.userId().toString(), command.warehouseId().toString(), command.reservationNo(), itemText));
    }

    private void appendReturnStockedEvent(
            InventoryReturnEntity inventoryReturn,
            List<ReturnInspectedItem> items,
            Instant now) {
        List<Map<String, Object>> eventItems = items.stream()
                .map(item -> Map.<String, Object>of(
                        "lineNo", item.lineNo(), "skuId", item.skuId(), "quantity", item.quantity()))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("afterSaleNo", inventoryReturn.getAfterSaleNo());
        payload.put("returnReceiptNo", inventoryReturn.getReturnReceiptNo());
        payload.put("orderNo", inventoryReturn.getOrderNo());
        payload.put("userId", inventoryReturn.getUserId());
        payload.put("warehouseId", inventoryReturn.getWarehouseId());
        payload.put("reservationNo", inventoryReturn.getReservationNo());
        payload.put("status", inventoryReturn.getStatus());
        payload.put("items", eventItems);

        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "ReturnStocked");
        envelope.put("aggregateType", "InventoryReturn");
        envelope.put("aggregateId", inventoryReturn.getAfterSaleNo());
        envelope.put("aggregateVersion", 1);
        envelope.put("occurredAt", now);
        envelope.put("producer", "inventory-service");
        envelope.put("payloadVersion", 1);
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(eventId);
        event.setEventType("ReturnStocked");
        event.setAggregateType("InventoryReturn");
        event.setAggregateId(inventoryReturn.getAfterSaleNo());
        event.setAggregateVersion(1);
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private InventoryReturnEntity requireReturn(String afterSaleNo) {
        InventoryReturnEntity result = returnMapper.selectOne(new LambdaQueryWrapper<InventoryReturnEntity>()
                .eq(InventoryReturnEntity::getAfterSaleNo, afterSaleNo));
        if (result == null) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return result;
    }

    private ReturnStockView view(InventoryReturnEntity result) {
        return new ReturnStockView(
                result.getAfterSaleNo(), result.getReturnReceiptNo(), result.getOrderNo(), result.getUserId(),
                result.getWarehouseId(), result.getReservationNo(), result.getStatus(),
                result.getCreatedAt(), result.getStockedAt());
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
        }
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
            throw new IllegalStateException("Unable to serialize inventory return event", exception);
        }
    }
}
