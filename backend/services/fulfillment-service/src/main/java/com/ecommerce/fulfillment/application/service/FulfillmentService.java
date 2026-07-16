package com.ecommerce.fulfillment.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.fulfillment.application.exception.FulfillmentError;
import com.ecommerce.fulfillment.application.exception.FulfillmentException;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AddTraceCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.DeliveryAddress;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.FulfillmentView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.LogisticsTraceView;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.OrderPaidCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.ShipCommand;
import com.ecommerce.fulfillment.domain.FulfillmentStatus;
import com.ecommerce.fulfillment.domain.LogisticsNodeType;
import com.ecommerce.fulfillment.domain.OutboxStatus;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentOrderEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.FulfillmentStatusHistoryEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.LogisticsTraceEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.FulfillmentOrderMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.FulfillmentStatusHistoryMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.LogisticsTraceMapper;
import com.ecommerce.fulfillment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class FulfillmentService {

    public static final String ORDER_PAID_CONSUMER_GROUP = "fulfillment-order-paid-v1";

    private final FulfillmentOrderMapper orderMapper;
    private final FulfillmentStatusHistoryMapper historyMapper;
    private final LogisticsTraceMapper traceMapper;
    private final OutboxEventMapper outboxMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FulfillmentService(
            FulfillmentOrderMapper orderMapper,
            FulfillmentStatusHistoryMapper historyMapper,
            LogisticsTraceMapper traceMapper,
            OutboxEventMapper outboxMapper,
            ConsumedEventMapper consumedEventMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.orderMapper = orderMapper;
        this.historyMapper = historyMapper;
        this.traceMapper = traceMapper;
        this.outboxMapper = outboxMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public FulfillmentView createFromOrderPaid(OrderPaidCommand command) {
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(), ORDER_PAID_CONSUMER_GROUP, clock.instant()) != 1) {
            return view(requireByOrderNo(command.orderNo()));
        }

        FulfillmentOrderEntity existing = orderMapper.selectByOrderNoForUpdate(command.orderNo());
        if (existing != null) {
            if (!existing.getUserId().equals(command.userId())
                    || !addressMatches(existing, command.deliveryAddress())) {
                throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
            }
            return view(existing);
        }

        Instant now = clock.instant();
        long id = IdWorker.getId();
        FulfillmentOrderEntity order = new FulfillmentOrderEntity();
        order.setId(id);
        order.setFulfillmentNo("FUL" + id);
        order.setOrderNo(command.orderNo());
        order.setUserId(command.userId());
        applyAddress(order, command.deliveryAddress());
        order.setStatus(FulfillmentStatus.CREATED.name());
        order.setVersion(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);
        appendHistory(order, null, FulfillmentStatus.CREATED.name(), "CREATE_FULFILLMENT",
                null, "SYSTEM", "trade-service", now);
        appendEvent(order, "FulfillmentCreated", now);
        return view(order);
    }

    @Transactional
    public FulfillmentView startPicking(String fulfillmentNo, String operatorId) {
        FulfillmentOrderEntity order = requireLocked(fulfillmentNo);
        if (FulfillmentStatus.PICKING.name().equals(order.getStatus())) {
            return view(order);
        }
        requireStatus(order, FulfillmentStatus.CREATED);
        order.setPickedAt(clock.instant());
        transition(order, FulfillmentStatus.PICKING, "START_PICKING", null,
                "WAREHOUSE", operatorId, null);
        return view(order);
    }

    @Transactional
    public FulfillmentView markPacked(String fulfillmentNo, String operatorId) {
        FulfillmentOrderEntity order = requireLocked(fulfillmentNo);
        if (FulfillmentStatus.PACKED.name().equals(order.getStatus())) {
            return view(order);
        }
        requireStatus(order, FulfillmentStatus.PICKING);
        order.setPackedAt(clock.instant());
        transition(order, FulfillmentStatus.PACKED, "MARK_PACKED", null,
                "WAREHOUSE", operatorId, null);
        return view(order);
    }

    @Transactional
    public FulfillmentView ship(String fulfillmentNo, ShipCommand command) {
        FulfillmentOrderEntity order = requireLocked(fulfillmentNo);
        if (FulfillmentStatus.SHIPPED.name().equals(order.getStatus())
                && command.carrier().equals(order.getCarrier())
                && command.trackingNo().equals(order.getTrackingNo())) {
            return view(order);
        }
        requireStatus(order, FulfillmentStatus.PACKED);
        order.setCarrier(command.carrier());
        order.setTrackingNo(command.trackingNo());
        order.setShippedAt(clock.instant());
        try {
            transition(order, FulfillmentStatus.SHIPPED, "SHIP", null,
                    "WAREHOUSE", command.operatorId(), "ShipmentDispatched");
        } catch (DataIntegrityViolationException exception) {
            throw new FulfillmentException(FulfillmentError.DUPLICATE_TRACKING);
        }
        return view(order);
    }

    @Transactional
    public FulfillmentView addTrace(String fulfillmentNo, AddTraceCommand command) {
        FulfillmentOrderEntity order = requireLocked(fulfillmentNo);
        if (order.getCarrier() == null || order.getTrackingNo() == null) {
            throw new FulfillmentException(FulfillmentError.INVALID_STATE);
        }
        validateCoordinates(command.longitude(), command.latitude());
        LogisticsNodeType node = parseNode(command.nodeType());
        String hash = traceHash(command);
        LogisticsTraceEntity existing = findTrace(order, command.externalEventId());
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getRequestHash().getBytes(StandardCharsets.UTF_8),
                    hash.getBytes(StandardCharsets.UTF_8))) {
                throw new FulfillmentException(FulfillmentError.IDEMPOTENCY_CONFLICT);
            }
            return view(order);
        }

        FulfillmentStatus target = targetForTrace(order, node);
        Instant now = clock.instant();
        LogisticsTraceEntity trace = new LogisticsTraceEntity();
        trace.setId(IdWorker.getId());
        trace.setFulfillmentId(order.getId());
        trace.setCarrier(order.getCarrier());
        trace.setTrackingNo(order.getTrackingNo());
        trace.setExternalEventId(command.externalEventId());
        trace.setRequestHash(hash);
        trace.setNodeType(node.name());
        trace.setDescription(command.description());
        trace.setLocationName(command.locationName());
        trace.setLongitude(command.longitude());
        trace.setLatitude(command.latitude());
        trace.setOccurredAt(command.occurredAt());
        trace.setCreatedAt(now);
        traceMapper.insert(trace);

        if (node == LogisticsNodeType.SIGNED) {
            order.setSignedAt(command.occurredAt());
        }
        String eventType = node == LogisticsNodeType.SIGNED ? "ShipmentSigned" : "LogisticsTraceAdded";
        if (!target.name().equals(order.getStatus())) {
            transition(order, target, "ADD_LOGISTICS_TRACE", command.description(),
                    "CARRIER", command.operatorId(), eventType);
        } else {
            order.setUpdatedAt(now);
            requireUpdated(orderMapper.updateById(order));
            appendEvent(order, eventType, now);
        }
        return view(order);
    }

    @Transactional
    public FulfillmentView markException(String fulfillmentNo, String reason, String operatorId) {
        FulfillmentOrderEntity order = requireLocked(fulfillmentNo);
        if (FulfillmentStatus.EXCEPTION.name().equals(order.getStatus())) {
            return view(order);
        }
        FulfillmentStatus current = FulfillmentStatus.valueOf(order.getStatus());
        if (!List.of(FulfillmentStatus.PICKING, FulfillmentStatus.SHIPPED,
                FulfillmentStatus.IN_TRANSIT, FulfillmentStatus.DELIVERING).contains(current)) {
            throw new FulfillmentException(FulfillmentError.INVALID_STATE);
        }
        transition(order, FulfillmentStatus.EXCEPTION, "MARK_EXCEPTION", reason,
                "WAREHOUSE", operatorId, "FulfillmentExceptionDetected");
        return view(order);
    }

    public FulfillmentView getForUser(String orderNo, Long userId) {
        FulfillmentOrderEntity order = requireByOrderNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new FulfillmentException(FulfillmentError.ACCESS_DENIED);
        }
        return view(order);
    }

    public List<FulfillmentView> listForUser(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<FulfillmentOrderEntity>()
                        .eq(FulfillmentOrderEntity::getUserId, userId)
                        .orderByDesc(FulfillmentOrderEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public FulfillmentView get(String fulfillmentNo) {
        FulfillmentOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<FulfillmentOrderEntity>()
                .eq(FulfillmentOrderEntity::getFulfillmentNo, fulfillmentNo));
        if (order == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return view(order);
    }

    public List<FulfillmentView> list(String status) {
        LambdaQueryWrapper<FulfillmentOrderEntity> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            try {
                query.eq(FulfillmentOrderEntity::getStatus, FulfillmentStatus.valueOf(status).name());
            } catch (IllegalArgumentException exception) {
                throw new FulfillmentException(FulfillmentError.INVALID_STATE);
            }
        }
        query.orderByDesc(FulfillmentOrderEntity::getCreatedAt);
        return orderMapper.selectList(query).stream().map(this::view).toList();
    }

    private FulfillmentStatus targetForTrace(FulfillmentOrderEntity order, LogisticsNodeType node) {
        FulfillmentStatus current = FulfillmentStatus.valueOf(order.getStatus());
        return switch (node) {
            case TRANSIT -> {
                if (current != FulfillmentStatus.SHIPPED && current != FulfillmentStatus.IN_TRANSIT) {
                    throw new FulfillmentException(FulfillmentError.INVALID_STATE);
                }
                yield FulfillmentStatus.IN_TRANSIT;
            }
            case DELIVERING -> {
                if (current != FulfillmentStatus.IN_TRANSIT && current != FulfillmentStatus.DELIVERING) {
                    throw new FulfillmentException(FulfillmentError.INVALID_STATE);
                }
                yield FulfillmentStatus.DELIVERING;
            }
            case SIGNED -> {
                if (current != FulfillmentStatus.DELIVERING) {
                    throw new FulfillmentException(FulfillmentError.INVALID_STATE);
                }
                yield FulfillmentStatus.SIGNED;
            }
            case EXCEPTION -> {
                if (current != FulfillmentStatus.IN_TRANSIT && current != FulfillmentStatus.DELIVERING) {
                    throw new FulfillmentException(FulfillmentError.INVALID_STATE);
                }
                yield FulfillmentStatus.EXCEPTION;
            }
        };
    }

    private LogisticsNodeType parseNode(String value) {
        try {
            return LogisticsNodeType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new FulfillmentException(FulfillmentError.INVALID_TRACE);
        }
    }

    private void validateCoordinates(BigDecimal longitude, BigDecimal latitude) {
        if ((longitude == null) != (latitude == null)) {
            throw new FulfillmentException(FulfillmentError.INVALID_TRACE);
        }
    }

    private LogisticsTraceEntity findTrace(FulfillmentOrderEntity order, String externalEventId) {
        return traceMapper.selectOne(new LambdaQueryWrapper<LogisticsTraceEntity>()
                .eq(LogisticsTraceEntity::getCarrier, order.getCarrier())
                .eq(LogisticsTraceEntity::getTrackingNo, order.getTrackingNo())
                .eq(LogisticsTraceEntity::getExternalEventId, externalEventId));
    }

    private String traceHash(AddTraceCommand command) {
        String canonical = String.join("|",
                command.externalEventId(), command.nodeType(), command.description(),
                Objects.toString(command.locationName(), ""), Objects.toString(command.longitude(), ""),
                Objects.toString(command.latitude(), ""), command.occurredAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void transition(FulfillmentOrderEntity order, FulfillmentStatus target, String command,
                            String reason, String operatorType, String operatorId, String eventType) {
        String from = order.getStatus();
        Instant now = clock.instant();
        order.setStatus(target.name());
        order.setVersion(order.getVersion() + 1);
        order.setUpdatedAt(now);
        requireUpdated(orderMapper.updateById(order));
        appendHistory(order, from, target.name(), command, reason, operatorType, operatorId, now);
        if (eventType != null) {
            appendEvent(order, eventType, now);
        }
    }

    private void appendHistory(FulfillmentOrderEntity order, String from, String to, String command,
                               String reason, String operatorType, String operatorId, Instant now) {
        FulfillmentStatusHistoryEntity history = new FulfillmentStatusHistoryEntity();
        history.setId(IdWorker.getId());
        history.setFulfillmentId(order.getId());
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setCommand(command);
        history.setReason(reason);
        history.setOperatorType(operatorType);
        history.setOperatorId(operatorId);
        history.setCreatedAt(now);
        historyMapper.insert(history);
    }

    private void appendEvent(FulfillmentOrderEntity order, String eventType, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "FulfillmentOrder");
        envelope.put("aggregateId", order.getFulfillmentNo());
        envelope.put("aggregateVersion", order.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "fulfillment-service");
        envelope.put("traceId", MDC.get("traceId"));
        envelope.put("payloadVersion", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fulfillmentNo", order.getFulfillmentNo());
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("status", order.getStatus());
        payload.put("carrier", order.getCarrier());
        payload.put("trackingNo", order.getTrackingNo());
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("FulfillmentOrder");
        event.setAggregateId(order.getFulfillmentNo());
        event.setAggregateVersion(order.getVersion());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private FulfillmentOrderEntity requireLocked(String fulfillmentNo) {
        FulfillmentOrderEntity order = orderMapper.selectByFulfillmentNoForUpdate(fulfillmentNo);
        if (order == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return order;
    }

    private FulfillmentOrderEntity requireByOrderNo(String orderNo) {
        FulfillmentOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<FulfillmentOrderEntity>()
                .eq(FulfillmentOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw new FulfillmentException(FulfillmentError.RESOURCE_NOT_FOUND);
        }
        return order;
    }

    private void requireStatus(FulfillmentOrderEntity order, FulfillmentStatus expected) {
        if (!expected.name().equals(order.getStatus())) {
            throw new FulfillmentException(FulfillmentError.INVALID_STATE);
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new FulfillmentException(FulfillmentError.CONCURRENT_MODIFICATION);
        }
    }

    private void applyAddress(FulfillmentOrderEntity order, DeliveryAddress address) {
        order.setSourceAddressId(address.sourceAddressId());
        order.setRecipientName(address.recipientName());
        order.setPhone(address.phone());
        order.setProvince(address.province());
        order.setProvinceCode(address.provinceCode());
        order.setCity(address.city());
        order.setCityCode(address.cityCode());
        order.setDistrict(address.district());
        order.setDistrictCode(address.districtCode());
        order.setDetailAddress(address.detailAddress());
        order.setPostalCode(address.postalCode());
    }

    private boolean addressMatches(FulfillmentOrderEntity order, DeliveryAddress address) {
        return Objects.equals(order.getSourceAddressId(), address.sourceAddressId())
                && Objects.equals(order.getRecipientName(), address.recipientName())
                && Objects.equals(order.getPhone(), address.phone())
                && Objects.equals(order.getProvince(), address.province())
                && Objects.equals(order.getProvinceCode(), address.provinceCode())
                && Objects.equals(order.getCity(), address.city())
                && Objects.equals(order.getCityCode(), address.cityCode())
                && Objects.equals(order.getDistrict(), address.district())
                && Objects.equals(order.getDistrictCode(), address.districtCode())
                && Objects.equals(order.getDetailAddress(), address.detailAddress())
                && Objects.equals(order.getPostalCode(), address.postalCode());
    }

    private FulfillmentView view(FulfillmentOrderEntity order) {
        List<LogisticsTraceView> traces = traceMapper.selectList(
                        new LambdaQueryWrapper<LogisticsTraceEntity>()
                                .eq(LogisticsTraceEntity::getFulfillmentId, order.getId())
                                .orderByAsc(LogisticsTraceEntity::getOccurredAt)
                                .orderByAsc(LogisticsTraceEntity::getId))
                .stream().map(trace -> new LogisticsTraceView(
                        trace.getExternalEventId(), trace.getNodeType(), trace.getDescription(),
                        trace.getLocationName(), trace.getLongitude(), trace.getLatitude(), trace.getOccurredAt()))
                .toList();
        DeliveryAddress address = new DeliveryAddress(
                order.getSourceAddressId(), order.getRecipientName(), order.getPhone(), order.getProvince(),
                order.getProvinceCode(), order.getCity(), order.getCityCode(), order.getDistrict(),
                order.getDistrictCode(), order.getDetailAddress(), order.getPostalCode());
        return new FulfillmentView(order.getFulfillmentNo(), order.getOrderNo(), order.getUserId(), address,
                order.getStatus(), order.getCarrier(), order.getTrackingNo(), traces, order.getVersion(),
                order.getCreatedAt(), order.getUpdatedAt(), order.getPickedAt(), order.getPackedAt(),
                order.getShippedAt(), order.getSignedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize fulfillment event", exception);
        }
    }
}
