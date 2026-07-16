package com.ecommerce.inventory.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.inventory.application.exception.InventoryError;
import com.ecommerce.inventory.application.exception.InventoryException;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationItemView;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationLineCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReservationView;
import com.ecommerce.inventory.application.model.InventoryModels.ReserveInventoryCommand;
import com.ecommerce.inventory.application.model.InventoryModels.StockPosition;
import com.ecommerce.inventory.application.model.InventoryModels.StockSummary;
import com.ecommerce.inventory.application.model.InventoryModels.WarehouseView;
import com.ecommerce.inventory.domain.MovementType;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.WarehouseStatus;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryBalanceEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReservationEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.InventoryReservationItemEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.StockAdjustmentEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.ecommerce.inventory.infrastructure.persistence.entity.WarehouseEntity;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryBalanceMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReservationItemMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.InventoryReservationMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.StockAdjustmentMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.StockMovementMapper;
import com.ecommerce.inventory.infrastructure.persistence.mapper.WarehouseMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private final WarehouseMapper warehouseMapper;
    private final InventoryBalanceMapper balanceMapper;
    private final StockAdjustmentMapper adjustmentMapper;
    private final InventoryReservationMapper reservationMapper;
    private final InventoryReservationItemMapper reservationItemMapper;
    private final StockMovementMapper movementMapper;
    private final OutboxEventMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InventoryService(
            WarehouseMapper warehouseMapper,
            InventoryBalanceMapper balanceMapper,
            StockAdjustmentMapper adjustmentMapper,
            InventoryReservationMapper reservationMapper,
            InventoryReservationItemMapper reservationItemMapper,
            StockMovementMapper movementMapper,
            OutboxEventMapper outboxMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.warehouseMapper = warehouseMapper;
        this.balanceMapper = balanceMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.reservationMapper = reservationMapper;
        this.reservationItemMapper = reservationItemMapper;
        this.movementMapper = movementMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public WarehouseView createWarehouse(String code, String name) {
        Instant now = clock.instant();
        WarehouseEntity warehouse = new WarehouseEntity();
        warehouse.setCode(code);
        warehouse.setName(name);
        warehouse.setStatus(WarehouseStatus.ACTIVE.name());
        warehouse.setVersion(0);
        warehouse.setCreatedAt(now);
        warehouse.setUpdatedAt(now);
        warehouseMapper.insert(warehouse);
        return warehouseView(warehouse);
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> listWarehouses() {
        return warehouseMapper.selectList(new LambdaQueryWrapper<WarehouseEntity>()
                        .orderByAsc(WarehouseEntity::getCode))
                .stream().map(this::warehouseView).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseView getActiveWarehouseByCode(String code) {
        WarehouseEntity warehouse = warehouseMapper.selectOne(new LambdaQueryWrapper<WarehouseEntity>()
                .eq(WarehouseEntity::getCode, code)
                .eq(WarehouseEntity::getStatus, WarehouseStatus.ACTIVE.name()));
        if (warehouse == null) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return warehouseView(warehouse);
    }

    @Transactional
    public StockPosition adjustStock(
            String movementNo,
            Long warehouseId,
            Long skuId,
            long quantityDelta,
            String reason) {
        if (quantityDelta == 0) {
            throw new InventoryException(InventoryError.INVALID_ADJUSTMENT);
        }
        requireActiveWarehouse(warehouseId);
        String requestHash = sha256(String.join("|",
                movementNo, warehouseId.toString(), skuId.toString(), Long.toString(quantityDelta), reason));
        Instant now = clock.instant();

        StockAdjustmentEntity candidate = new StockAdjustmentEntity();
        candidate.setId(IdWorker.getId());
        candidate.setMovementNo(movementNo);
        candidate.setRequestHash(requestHash);
        candidate.setWarehouseId(warehouseId);
        candidate.setSkuId(skuId);
        candidate.setQuantityDelta(quantityDelta);
        candidate.setReason(reason);
        candidate.setStatus("PENDING");
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        int inserted = adjustmentMapper.insertIfAbsent(candidate);

        StockAdjustmentEntity adjustment = adjustmentMapper.selectForUpdate(movementNo);
        if (adjustment == null) {
            throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
        }
        requireSameHash(adjustment.getRequestHash(), requestHash);
        if (inserted == 0) {
            if (!"APPLIED".equals(adjustment.getStatus())) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
            return stockPosition(requireBalance(warehouseId, skuId));
        }

        balanceMapper.insertZeroIfAbsent(IdWorker.getId(), warehouseId, skuId, now);
        if (balanceMapper.adjustOnHand(warehouseId, skuId, quantityDelta, now) != 1) {
            throw new InventoryException(InventoryError.INVALID_ADJUSTMENT);
        }
        InventoryBalanceEntity balance = balanceMapper.selectForUpdate(warehouseId, skuId);
        insertMovement(movementNo, warehouseId, skuId, null, MovementType.ADJUSTMENT,
                quantityDelta, balance, reason, now);

        adjustment.setStatus("APPLIED");
        adjustment.setUpdatedAt(now);
        requireUpdated(adjustmentMapper.updateById(adjustment));
        appendOutboxEvent(
                "StockAdjusted",
                "InventoryBalance",
                warehouseId + ":" + skuId,
                balance.getVersion(),
                Map.of(
                        "movementNo", movementNo,
                        "warehouseId", warehouseId,
                        "skuId", skuId,
                        "quantityDelta", quantityDelta,
                        "onHand", balance.getOnHand(),
                        "reserved", balance.getReserved()
                ),
                now
        );
        return stockPosition(balance);
    }

    @Transactional(readOnly = true)
    public StockSummary getStockSummary(Long skuId) {
        List<InventoryBalanceEntity> balances = balanceMapper.selectList(
                new LambdaQueryWrapper<InventoryBalanceEntity>()
                        .eq(InventoryBalanceEntity::getSkuId, skuId));
        long onHand = balances.stream().mapToLong(InventoryBalanceEntity::getOnHand).sum();
        long reserved = balances.stream().mapToLong(InventoryBalanceEntity::getReserved).sum();
        return new StockSummary(skuId, onHand, reserved, onHand - reserved);
    }

    @Transactional(readOnly = true)
    public StockPosition getStockPosition(Long warehouseId, Long skuId) {
        return stockPosition(requireBalance(warehouseId, skuId));
    }

    @Transactional
    public ReservationView reserve(ReserveInventoryCommand command) {
        List<ReservationLineCommand> items = normalizeItems(command.items());
        String requestHash = reservationHash(command, items);
        requireActiveWarehouse(command.warehouseId());
        Instant now = clock.instant();

        InventoryReservationEntity candidate = new InventoryReservationEntity();
        candidate.setId(IdWorker.getId());
        candidate.setReservationNo(command.reservationNo());
        candidate.setOrderNo(command.orderNo());
        candidate.setRequestHash(requestHash);
        candidate.setWarehouseId(command.warehouseId());
        candidate.setStatus(ReservationStatus.PENDING.name());
        candidate.setExpiresAt(command.expiresAt());
        candidate.setVersion(0);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        int inserted = reservationMapper.insertIfAbsent(candidate);

        InventoryReservationEntity reservation = reservationMapper.selectForUpdate(command.reservationNo());
        if (reservation == null) {
            throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
        }
        requireSameHash(reservation.getRequestHash(), requestHash);
        if (inserted == 0) {
            return reservationView(reservation);
        }

        for (ReservationLineCommand item : items) {
            InventoryReservationItemEntity entity = new InventoryReservationItemEntity();
            entity.setReservationId(reservation.getId());
            entity.setSkuId(item.skuId());
            entity.setQuantity(item.quantity());
            entity.setCreatedAt(now);
            reservationItemMapper.insert(entity);
        }

        List<ReservationLineCommand> applied = new ArrayList<>();
        for (ReservationLineCommand item : items) {
            if (balanceMapper.reserve(command.warehouseId(), item.skuId(), item.quantity(), now) != 1) {
                rollbackReserved(command.warehouseId(), applied, now);
                transitionReservation(reservation, ReservationStatus.REJECTED, now);
                appendReservationEvent(reservation, "InventoryReservationRejected", items, now);
                return reservationView(reservation);
            }
            applied.add(item);
        }

        for (ReservationLineCommand item : items) {
            InventoryBalanceEntity balance = balanceMapper.selectForUpdate(command.warehouseId(), item.skuId());
            insertMovement(
                    movementNo(command.reservationNo(), MovementType.RESERVE, item.skuId()),
                    command.warehouseId(),
                    item.skuId(),
                    command.reservationNo(),
                    MovementType.RESERVE,
                    item.quantity(),
                    balance,
                    "Inventory reserved for order " + command.orderNo(),
                    now
            );
        }
        transitionReservation(reservation, ReservationStatus.RESERVED, now);
        appendReservationEvent(reservation, "InventoryReserved", items, now);
        return reservationView(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationView getReservation(String reservationNo) {
        InventoryReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<InventoryReservationEntity>()
                        .eq(InventoryReservationEntity::getReservationNo, reservationNo));
        if (reservation == null) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return reservationView(reservation);
    }

    @Transactional
    public ReservationView confirmReservation(String reservationNo) {
        InventoryReservationEntity reservation = requireLockedReservation(reservationNo);
        if (ReservationStatus.CONFIRMED.name().equals(reservation.getStatus())) {
            return reservationView(reservation);
        }
        requireReserved(reservation);
        List<InventoryReservationItemEntity> items = reservationItems(reservation.getId());
        Instant now = clock.instant();
        for (InventoryReservationItemEntity item : items) {
            if (balanceMapper.confirm(reservation.getWarehouseId(), item.getSkuId(), item.getQuantity(), now) != 1) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
            InventoryBalanceEntity balance = balanceMapper.selectForUpdate(
                    reservation.getWarehouseId(), item.getSkuId());
            insertMovement(
                    movementNo(reservationNo, MovementType.CONFIRM, item.getSkuId()),
                    reservation.getWarehouseId(), item.getSkuId(), reservationNo,
                    MovementType.CONFIRM, -item.getQuantity(), balance,
                    "Reserved inventory confirmed", now);
        }
        transitionReservation(reservation, ReservationStatus.CONFIRMED, now);
        appendReservationEvent(reservation, "InventoryReservationConfirmed", toCommands(items), now);
        return reservationView(reservation);
    }

    @Transactional
    public ReservationView releaseReservation(String reservationNo) {
        return releaseReservation(reservationNo, ReservationStatus.RELEASED, false);
    }

    @Transactional
    public ReservationView expireReservation(String reservationNo) {
        return releaseReservation(reservationNo, ReservationStatus.EXPIRED, true);
    }

    @Transactional(readOnly = true)
    public List<String> findExpiredReservationNumbers(int limit) {
        return reservationMapper.selectExpiredReservationNumbers(clock.instant(), limit);
    }

    private ReservationView releaseReservation(
            String reservationNo,
            ReservationStatus targetStatus,
            boolean requireExpired) {
        InventoryReservationEntity reservation = requireLockedReservation(reservationNo);
        if (targetStatus.name().equals(reservation.getStatus())) {
            return reservationView(reservation);
        }
        requireReserved(reservation);
        Instant now = clock.instant();
        if (requireExpired && reservation.getExpiresAt().isAfter(now)) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
        List<InventoryReservationItemEntity> items = reservationItems(reservation.getId());
        MovementType movementType = targetStatus == ReservationStatus.EXPIRED
                ? MovementType.EXPIRE : MovementType.RELEASE;
        for (InventoryReservationItemEntity item : items) {
            if (balanceMapper.release(reservation.getWarehouseId(), item.getSkuId(), item.getQuantity(), now) != 1) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
            InventoryBalanceEntity balance = balanceMapper.selectForUpdate(
                    reservation.getWarehouseId(), item.getSkuId());
            insertMovement(
                    movementNo(reservationNo, movementType, item.getSkuId()),
                    reservation.getWarehouseId(), item.getSkuId(), reservationNo,
                    movementType, -item.getQuantity(), balance,
                    targetStatus == ReservationStatus.EXPIRED
                            ? "Reservation expired" : "Reservation released",
                    now);
        }
        transitionReservation(reservation, targetStatus, now);
        appendReservationEvent(
                reservation,
                targetStatus == ReservationStatus.EXPIRED
                        ? "InventoryReservationExpired" : "InventoryReservationReleased",
                toCommands(items),
                now
        );
        return reservationView(reservation);
    }

    private void rollbackReserved(Long warehouseId, List<ReservationLineCommand> applied, Instant now) {
        for (ReservationLineCommand item : applied) {
            if (balanceMapper.release(warehouseId, item.skuId(), item.quantity(), now) != 1) {
                throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
            }
        }
    }

    private void transitionReservation(
            InventoryReservationEntity reservation,
            ReservationStatus target,
            Instant now) {
        reservation.setStatus(target.name());
        reservation.setUpdatedAt(now);
        requireUpdated(reservationMapper.updateById(reservation));
    }

    private void appendReservationEvent(
            InventoryReservationEntity reservation,
            String eventType,
            List<ReservationLineCommand> items,
            Instant now) {
        List<Map<String, Object>> payloadItems = items.stream()
                .map(item -> Map.<String, Object>of("skuId", item.skuId(), "quantity", item.quantity()))
                .toList();
        appendOutboxEvent(
                eventType,
                "InventoryReservation",
                reservation.getReservationNo(),
                reservation.getVersion(),
                Map.of(
                        "reservationNo", reservation.getReservationNo(),
                        "orderNo", reservation.getOrderNo(),
                        "warehouseId", reservation.getWarehouseId(),
                        "status", reservation.getStatus(),
                        "expiresAt", reservation.getExpiresAt(),
                        "items", payloadItems
                ),
                now
        );
    }

    private void appendOutboxEvent(
            String eventType,
            String aggregateType,
            String aggregateId,
            int aggregateVersion,
            Map<String, Object> eventPayload,
            Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("occurredAt", now);
        envelope.put("producer", "inventory-service");
        envelope.put("payloadVersion", 1);
        envelope.put("payload", eventPayload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setAggregateVersion(aggregateVersion);
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize inventory event", exception);
        }
    }

    private List<ReservationLineCommand> normalizeItems(List<ReservationLineCommand> input) {
        if (input.isEmpty()) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
        Map<Long, Long> quantities = new LinkedHashMap<>();
        for (ReservationLineCommand item : input) {
            if (item.skuId() == null || item.skuId() <= 0 || item.quantity() <= 0) {
                throw new InventoryException(InventoryError.INVALID_STATE);
            }
            if (quantities.putIfAbsent(item.skuId(), item.quantity()) != null) {
                throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
            }
        }
        return quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ReservationLineCommand(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String reservationHash(ReserveInventoryCommand command, List<ReservationLineCommand> items) {
        String itemText = items.stream()
                .map(item -> item.skuId() + ":" + item.quantity())
                .collect(Collectors.joining(","));
        return sha256(String.join("|",
                command.reservationNo(), command.orderNo(), command.warehouseId().toString(), itemText));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private WarehouseEntity requireActiveWarehouse(Long warehouseId) {
        WarehouseEntity warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null || !WarehouseStatus.ACTIVE.name().equals(warehouse.getStatus())) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return warehouse;
    }

    private InventoryBalanceEntity requireBalance(Long warehouseId, Long skuId) {
        InventoryBalanceEntity balance = balanceMapper.selectOne(
                new LambdaQueryWrapper<InventoryBalanceEntity>()
                        .eq(InventoryBalanceEntity::getWarehouseId, warehouseId)
                        .eq(InventoryBalanceEntity::getSkuId, skuId));
        if (balance == null) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return balance;
    }

    private InventoryReservationEntity requireLockedReservation(String reservationNo) {
        InventoryReservationEntity reservation = reservationMapper.selectForUpdate(reservationNo);
        if (reservation == null) {
            throw new InventoryException(InventoryError.RESOURCE_NOT_FOUND);
        }
        return reservation;
    }

    private void requireReserved(InventoryReservationEntity reservation) {
        if (!ReservationStatus.RESERVED.name().equals(reservation.getStatus())) {
            throw new InventoryException(InventoryError.INVALID_STATE);
        }
    }

    private void requireSameHash(String actualHash, String expectedHash) {
        if (!expectedHash.equals(actualHash)) {
            throw new InventoryException(InventoryError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new InventoryException(InventoryError.CONCURRENT_MODIFICATION);
        }
    }

    private List<InventoryReservationItemEntity> reservationItems(Long reservationId) {
        return reservationItemMapper.selectList(
                new LambdaQueryWrapper<InventoryReservationItemEntity>()
                        .eq(InventoryReservationItemEntity::getReservationId, reservationId)
                        .orderByAsc(InventoryReservationItemEntity::getSkuId));
    }

    private List<ReservationLineCommand> toCommands(List<InventoryReservationItemEntity> items) {
        return items.stream()
                .map(item -> new ReservationLineCommand(item.getSkuId(), item.getQuantity()))
                .toList();
    }

    private void insertMovement(
            String movementNo,
            Long warehouseId,
            Long skuId,
            String reservationNo,
            MovementType movementType,
            long quantityDelta,
            InventoryBalanceEntity balance,
            String reason,
            Instant now) {
        StockMovementEntity movement = new StockMovementEntity();
        movement.setMovementNo(movementNo);
        movement.setWarehouseId(warehouseId);
        movement.setSkuId(skuId);
        movement.setReservationNo(reservationNo);
        movement.setMovementType(movementType.name());
        movement.setQuantityDelta(quantityDelta);
        movement.setOnHandAfter(balance.getOnHand());
        movement.setReservedAfter(balance.getReserved());
        movement.setReason(reason);
        movement.setCreatedAt(now);
        movementMapper.insert(movement);
    }

    private String movementNo(String reservationNo, MovementType movementType, Long skuId) {
        return reservationNo + ":" + movementType.name() + ":" + skuId;
    }

    private WarehouseView warehouseView(WarehouseEntity warehouse) {
        return new WarehouseView(warehouse.getId(), warehouse.getCode(), warehouse.getName(),
                warehouse.getStatus(), warehouse.getVersion());
    }

    private StockPosition stockPosition(InventoryBalanceEntity balance) {
        return new StockPosition(
                balance.getWarehouseId(),
                balance.getSkuId(),
                balance.getOnHand(),
                balance.getReserved(),
                balance.getOnHand() - balance.getReserved(),
                balance.getVersion()
        );
    }

    private ReservationView reservationView(InventoryReservationEntity reservation) {
        List<ReservationItemView> items = reservationItems(reservation.getId()).stream()
                .map(item -> new ReservationItemView(item.getSkuId(), item.getQuantity()))
                .toList();
        return new ReservationView(
                reservation.getReservationNo(),
                reservation.getOrderNo(),
                reservation.getWarehouseId(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getVersion(),
                items
        );
    }
}
