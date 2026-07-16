package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.AddressSnapshotView;
import com.ecommerce.trade.application.model.TradeModels.CreateOrderCommand;
import com.ecommerce.trade.application.model.TradeModels.FulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.DiscountAllocationView;
import com.ecommerce.trade.application.model.TradeModels.OrderItemView;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.application.model.TradeModels.PaymentContextView;
import com.ecommerce.trade.application.model.TradeModels.PaymentSucceededCommand;
import com.ecommerce.trade.application.model.TradeModels.PriceSnapshotView;
import com.ecommerce.trade.application.port.AddressPort;
import com.ecommerce.trade.application.port.AddressPort.AddressSnapshot;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.application.port.InventoryPort.ReservationCommand;
import com.ecommerce.trade.application.port.InventoryPort.ReservationLine;
import com.ecommerce.trade.application.port.InventoryPort.ReservationSnapshot;
import com.ecommerce.trade.application.port.MarketingPort;
import com.ecommerce.trade.application.port.MarketingPort.AppliedBenefit;
import com.ecommerce.trade.application.port.MarketingPort.DiscountAllocation;
import com.ecommerce.trade.application.port.MarketingPort.PricingCommand;
import com.ecommerce.trade.application.port.MarketingPort.PricingLine;
import com.ecommerce.trade.application.port.MarketingPort.PricingLock;
import com.ecommerce.trade.application.service.CatalogSnapshotService.ResolvedLine;
import com.ecommerce.trade.domain.OrderStatus;
import com.ecommerce.trade.domain.OutboxStatus;
import com.ecommerce.trade.infrastructure.config.OrderProperties;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderAddressSnapshotEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderBenefitSelectionEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderDiscountAllocationEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderItemEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderPriceSnapshotEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OrderStatusHistoryEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.TradeOrderEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderItemMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderAddressSnapshotMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderBenefitSelectionMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderDiscountAllocationMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderPriceSnapshotMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OrderStatusHistoryMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.ConsumedEventMapper;
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
import java.util.Set;
import java.util.UUID;

@Service
public class TradeOrderService {

    public static final String PAYMENT_CONSUMER_GROUP = "trade-payment-succeeded-v1";
    public static final String FULFILLMENT_CONSUMER_GROUP = "trade-fulfillment-events-v1";

    private final TradeOrderMapper orderMapper;
    private final OrderAddressSnapshotMapper addressSnapshotMapper;
    private final OrderBenefitSelectionMapper benefitSelectionMapper;
    private final OrderPriceSnapshotMapper priceSnapshotMapper;
    private final OrderDiscountAllocationMapper discountAllocationMapper;
    private final OrderItemMapper itemMapper;
    private final OrderStatusHistoryMapper historyMapper;
    private final OutboxEventMapper outboxMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final CatalogSnapshotService snapshotService;
    private final AddressPort addressPort;
    private final InventoryPort inventoryPort;
    private final MarketingPort marketingPort;
    private final OrderProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TradeOrderService(
            TradeOrderMapper orderMapper,
            OrderAddressSnapshotMapper addressSnapshotMapper,
            OrderBenefitSelectionMapper benefitSelectionMapper,
            OrderPriceSnapshotMapper priceSnapshotMapper,
            OrderDiscountAllocationMapper discountAllocationMapper,
            OrderItemMapper itemMapper,
            OrderStatusHistoryMapper historyMapper,
            OutboxEventMapper outboxMapper,
            ConsumedEventMapper consumedEventMapper,
            CatalogSnapshotService snapshotService,
            AddressPort addressPort,
            InventoryPort inventoryPort,
            MarketingPort marketingPort,
            OrderProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.orderMapper = orderMapper;
        this.addressSnapshotMapper = addressSnapshotMapper;
        this.benefitSelectionMapper = benefitSelectionMapper;
        this.priceSnapshotMapper = priceSnapshotMapper;
        this.discountAllocationMapper = discountAllocationMapper;
        this.itemMapper = itemMapper;
        this.historyMapper = historyMapper;
        this.outboxMapper = outboxMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.snapshotService = snapshotService;
        this.addressPort = addressPort;
        this.inventoryPort = inventoryPort;
        this.marketingPort = marketingPort;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public OrderView createOrder(CreateOrderCommand command) {
        AddressSnapshot address = addressPort.getAddress(command.userId(), command.addressId());
        List<ResolvedLine> snapshots = snapshotService.resolve(command.items());
        String requestHash = requestHash(address, snapshots, command.benefitNos());
        BigDecimal originalTotal = snapshots.stream()
                .map(ResolvedLine::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = clock.instant();
        long id = IdWorker.getId();
        String orderNo = "ORD" + id;
        String reservationNo = "RSV" + id;

        OrderClaim claim = Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            TradeOrderEntity candidate = new TradeOrderEntity();
            candidate.setId(id);
            candidate.setOrderNo(orderNo);
            candidate.setUserId(command.userId());
            candidate.setIdempotencyKey(command.idempotencyKey());
            candidate.setRequestHash(requestHash);
            candidate.setReservationNo(reservationNo);
            candidate.setWarehouseCode(properties.defaultWarehouseCode());
            candidate.setStatus(OrderStatus.PENDING_STOCK.name());
            candidate.setOriginalAmount(originalTotal);
            candidate.setDiscountAmount(BigDecimal.ZERO.setScale(2));
            candidate.setTotalAmount(originalTotal);
            candidate.setPaymentDeadline(now.plus(properties.paymentTimeout()));
            candidate.setRecoveryAttempts(0);
            candidate.setNextRecoveryAt(now);
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            int inserted = orderMapper.insertIfAbsent(candidate);

            TradeOrderEntity order = orderMapper.selectByIdempotencyForUpdate(
                    command.userId(), command.idempotencyKey());
            if (order == null) {
                throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
            }
            if (!MessageDigest.isEqual(
                    order.getRequestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            if (inserted == 1) {
                insertAddressSnapshot(order.getId(), address, now);
                insertOrderItems(order.getId(), snapshots, now);
                insertBenefitSelections(order.getId(), command.benefitNos(), now);
                appendHistory(order, null, OrderStatus.PENDING_STOCK.name(), "CREATE_ORDER",
                        null, "CUSTOMER", command.userId().toString(), now);
                appendOrderEvent(order, "OrderCreated", now);
            }
            return new OrderClaim(order.getOrderNo(), inserted == 1);
        }));

        if (claim.created()) {
            progressPricingAndStockReservation(claim.orderNo());
        }
        return getOrder(command.userId(), claim.orderNo());
    }

    public OrderView getOrder(Long userId, String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        requireOwner(order, userId);
        return view(order);
    }

    public List<OrderView> listOrders(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<TradeOrderEntity>()
                        .eq(TradeOrderEntity::getUserId, userId)
                        .orderByDesc(TradeOrderEntity::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public PaymentContextView getPaymentContext(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        return new PaymentContextView(
                order.getOrderNo(),
                order.getUserId(),
                order.getReservationNo(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentDeadline());
    }

    public void applyPaymentSucceeded(PaymentSucceededCommand command) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), PAYMENT_CONSUMER_GROUP, clock.instant()) != 1) {
                return;
            }
            TradeOrderEntity order = requireLockedOrder(command.orderNo());
            if (!order.getUserId().equals(command.userId())
                    || !order.getReservationNo().equals(command.reservationNo())
                    || order.getTotalAmount().compareTo(command.amount()) != 0) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            if (OrderStatus.PAID.name().equals(order.getStatus())) {
                return;
            }
            if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                if (List.of(
                        OrderStatus.CANCELING.name(),
                        OrderStatus.CANCELED.name(),
                        OrderStatus.CLOSED.name()).contains(order.getStatus())) {
                    transition(order, OrderStatus.PAYMENT_EXCEPTION, "LATE_PAYMENT_DETECTED",
                            command.paymentNo(), "SYSTEM", "payment-service", "PaymentReviewRequired");
                    return;
                }
                throw new TradeException(TradeError.INVALID_STATE);
            }
            order.setNextRecoveryAt(null);
            order.setLastError(null);
            transition(order, OrderStatus.PAID, "PAYMENT_SUCCEEDED", command.paymentNo(),
                    "SYSTEM", "payment-service", "OrderPaid");
        });
    }

    public void applyFulfillmentEvent(FulfillmentEventCommand command) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), FULFILLMENT_CONSUMER_GROUP, clock.instant()) != 1) {
                return;
            }
            TradeOrderEntity order = requireLockedOrder(command.orderNo());
            if (!order.getUserId().equals(command.userId())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            switch (command.eventType()) {
                case "FulfillmentCreated" -> applyFulfillmentCreated(order, command.fulfillmentNo());
                case "ShipmentDispatched" -> applyShipmentDispatched(order, command.fulfillmentNo());
                case "ShipmentSigned" -> applyShipmentSigned(order, command.fulfillmentNo());
                default -> throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        });
    }

    private void applyFulfillmentCreated(TradeOrderEntity order, String fulfillmentNo) {
        if (List.of(OrderStatus.FULFILLING.name(), OrderStatus.SHIPPED.name(),
                OrderStatus.COMPLETED.name()).contains(order.getStatus())) {
            return;
        }
        if (!OrderStatus.PAID.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.FULFILLING, "FULFILLMENT_CREATED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderFulfilling");
    }

    private void applyShipmentDispatched(TradeOrderEntity order, String fulfillmentNo) {
        if (List.of(OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name()).contains(order.getStatus())) {
            return;
        }
        if (!OrderStatus.FULFILLING.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.SHIPPED, "SHIPMENT_DISPATCHED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderShipped");
    }

    private void applyShipmentSigned(TradeOrderEntity order, String fulfillmentNo) {
        if (OrderStatus.COMPLETED.name().equals(order.getStatus())) {
            return;
        }
        if (!OrderStatus.SHIPPED.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.COMPLETED, "SHIPMENT_SIGNED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderCompleted");
    }

    public OrderView cancelOrder(Long userId, String orderNo) {
        beginCancellation(orderNo, userId, "USER_CANCELED", "CUSTOMER", userId.toString(), false);
        progressCancellation(orderNo);
        return getOrder(userId, orderNo);
    }

    public void recoverOrder(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        if (OrderStatus.PENDING_STOCK.name().equals(order.getStatus())) {
            progressPricingAndStockReservation(orderNo);
        } else if (OrderStatus.CANCELING.name().equals(order.getStatus())) {
            progressCancellation(orderNo);
        }
    }

    public void cancelTimedOutOrder(String orderNo) {
        beginCancellation(orderNo, null, "PAYMENT_TIMEOUT", "SYSTEM", "trade-recovery", true);
        progressCancellation(orderNo);
    }

    public List<String> findRecoverableOrderNumbers(int limit) {
        return orderMapper.selectRecoverableOrderNumbers(clock.instant(), limit);
    }

    public List<String> findTimedOutOrderNumbers(int limit) {
        return orderMapper.selectTimedOutOrderNumbers(clock.instant(), limit);
    }

    private void progressPricingAndStockReservation(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        if (!OrderStatus.PENDING_STOCK.name().equals(order.getStatus())) {
            return;
        }
        if (order.getMarketingLockNo() == null) {
            PricingLock pricing;
            try {
                OrderAddressSnapshotEntity address = orderAddress(order.getId());
                List<OrderItemEntity> items = orderItems(order.getId());
                pricing = marketingPort.lockPricing(new PricingCommand(
                        order.getOrderNo(),
                        order.getUserId(),
                        order.getOriginalAmount(),
                        new MarketingPort.DeliveryRegion(
                                address.getProvinceCode(), address.getCityCode(), address.getDistrictCode()),
                        items.stream().map(item -> new PricingLine(
                                item.getLineNo(), item.getSkuId(), item.getLineAmount())).toList(),
                        selectedBenefits(order.getId())));
            } catch (MarketingPort.PricingRejectedException exception) {
                closeForMarketingRejection(orderNo);
                return;
            } catch (RuntimeException exception) {
                scheduleRecovery(orderNo, exception);
                return;
            }
            try {
                applyPricingLock(orderNo, pricing);
            } catch (RuntimeException exception) {
                scheduleRecovery(orderNo, exception);
                return;
            }
        }
        progressStockReservation(orderNo);
    }

    private void closeForMarketingRejection(String orderNo) {
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity order = requireLockedOrder(orderNo);
            if (!OrderStatus.PENDING_STOCK.name().equals(order.getStatus())
                    || order.getMarketingLockNo() != null) {
                return;
            }
            order.setCloseReason("MARKETING_REJECTED");
            order.setNextRecoveryAt(null);
            order.setLastError(null);
            transition(order, OrderStatus.CLOSED, "REJECT_MARKETING", "MARKETING_REJECTED",
                    "SYSTEM", "marketing-service", "OrderClosed");
        });
    }

    private void applyPricingLock(String orderNo, PricingLock pricing) {
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity order = requireLockedOrder(orderNo);
            if (!OrderStatus.PENDING_STOCK.name().equals(order.getStatus())) {
                return;
            }
            if (order.getMarketingLockNo() != null) {
                if (!order.getMarketingLockNo().equals(pricing.lockNo())) {
                    throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
                }
                return;
            }
            List<OrderItemEntity> items = orderItems(order.getId());
            validatePricing(order, items, pricing);
            Instant now = clock.instant();
            OrderPriceSnapshotEntity snapshot = new OrderPriceSnapshotEntity();
            snapshot.setId(IdWorker.getId());
            snapshot.setOrderId(order.getId());
            snapshot.setMarketingLockNo(pricing.lockNo());
            snapshot.setOriginalAmount(pricing.originalAmount());
            snapshot.setCouponDiscount(pricing.couponDiscount());
            snapshot.setRedPacketDiscount(pricing.redPacketDiscount());
            snapshot.setSubsidyDiscount(pricing.subsidyDiscount());
            snapshot.setDiscountAmount(pricing.discountAmount());
            snapshot.setPayableAmount(pricing.payableAmount());
            snapshot.setPricingVersion("marketing-v1");
            snapshot.setCreatedAt(now);
            priceSnapshotMapper.insert(snapshot);

            Map<Integer, BigDecimal> discountByLine = new LinkedHashMap<>();
            Map<Integer, OrderItemEntity> itemByLine = new LinkedHashMap<>();
            items.forEach(item -> itemByLine.put(item.getLineNo(), item));
            for (AppliedBenefit applied : pricing.appliedBenefits()) {
                for (DiscountAllocation source : applied.allocations()) {
                    OrderItemEntity item = itemByLine.get(source.lineNo());
                    OrderDiscountAllocationEntity allocation = new OrderDiscountAllocationEntity();
                    allocation.setId(IdWorker.getId());
                    allocation.setOrderId(order.getId());
                    allocation.setOrderItemId(item.getId());
                    allocation.setLineNo(source.lineNo());
                    allocation.setSkuId(source.skuId());
                    allocation.setBenefitNo(source.benefitNo());
                    allocation.setRuleCode(source.ruleCode());
                    allocation.setBenefitType(source.benefitType());
                    allocation.setDiscountAmount(source.discountAmount());
                    allocation.setCreatedAt(now);
                    discountAllocationMapper.insert(allocation);
                    discountByLine.merge(source.lineNo(), source.discountAmount(), BigDecimal::add);
                }
            }
            for (OrderItemEntity item : items) {
                BigDecimal discount = discountByLine.getOrDefault(item.getLineNo(), BigDecimal.ZERO.setScale(2));
                item.setDiscountAmount(discount);
                item.setPayableAmount(item.getLineAmount().subtract(discount));
                requireUpdated(itemMapper.updateById(item));
            }
            order.setOriginalAmount(pricing.originalAmount());
            order.setDiscountAmount(pricing.discountAmount());
            order.setTotalAmount(pricing.payableAmount());
            order.setMarketingLockNo(pricing.lockNo());
            order.setRecoveryAttempts(0);
            order.setNextRecoveryAt(clock.instant());
            order.setLastError(null);
            order.setUpdatedAt(now);
            requireUpdated(orderMapper.updateById(order));
        });
    }

    private void validatePricing(TradeOrderEntity order, List<OrderItemEntity> items, PricingLock pricing) {
        if (pricing == null || !"LOCKED".equals(pricing.status())
                || !order.getOrderNo().equals(pricing.orderNo())
                || !order.getUserId().equals(pricing.userId())
                || order.getOriginalAmount().compareTo(pricing.originalAmount()) != 0
                || pricing.originalAmount().compareTo(pricing.discountAmount().add(pricing.payableAmount())) != 0
                || pricing.discountAmount().compareTo(pricing.couponDiscount()
                .add(pricing.redPacketDiscount()).add(pricing.subsidyDiscount())) != 0) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
        Set<String> selected = Set.copyOf(selectedBenefits(order.getId()));
        Set<String> appliedNos = pricing.appliedBenefits().stream().map(AppliedBenefit::benefitNo)
                .collect(java.util.stream.Collectors.toSet());
        if (!selected.equals(appliedNos) || appliedNos.size() != pricing.appliedBenefits().size()) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
        Map<Integer, OrderItemEntity> itemByLine = items.stream()
                .collect(java.util.stream.Collectors.toMap(OrderItemEntity::getLineNo, item -> item));
        BigDecimal allAllocations = BigDecimal.ZERO;
        Map<Integer, BigDecimal> byLine = new LinkedHashMap<>();
        for (AppliedBenefit applied : pricing.appliedBenefits()) {
            BigDecimal appliedAllocations = BigDecimal.ZERO;
            for (DiscountAllocation allocation : applied.allocations()) {
                OrderItemEntity item = itemByLine.get(allocation.lineNo());
                if (item == null || !item.getSkuId().equals(allocation.skuId())
                        || !applied.benefitNo().equals(allocation.benefitNo())
                        || !applied.ruleCode().equals(allocation.ruleCode())
                        || !applied.benefitType().equals(allocation.benefitType())
                        || allocation.discountAmount() == null || allocation.discountAmount().signum() <= 0) {
                    throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
                }
                appliedAllocations = appliedAllocations.add(allocation.discountAmount());
                allAllocations = allAllocations.add(allocation.discountAmount());
                byLine.merge(allocation.lineNo(), allocation.discountAmount(), BigDecimal::add);
            }
            if (appliedAllocations.compareTo(applied.discountAmount()) != 0) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        }
        if (allAllocations.compareTo(pricing.discountAmount()) != 0
                || byLine.entrySet().stream().anyMatch(entry ->
                entry.getValue().compareTo(itemByLine.get(entry.getKey()).getLineAmount()) > 0)) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void progressStockReservation(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        if (!OrderStatus.PENDING_STOCK.name().equals(order.getStatus()) || order.getMarketingLockNo() == null) {
            return;
        }
        List<OrderItemEntity> items = orderItems(order.getId());
        Long warehouseId;
        ReservationSnapshot reservation;
        try {
            warehouseId = inventoryPort.getWarehouse(order.getWarehouseCode()).id();
            ReservationCommand command = new ReservationCommand(
                    order.getReservationNo(), order.getOrderNo(), warehouseId,
                    order.getPaymentDeadline().plus(properties.reservationGrace()),
                    items.stream().map(item -> new ReservationLine(item.getSkuId(), item.getQuantity())).toList());
            try {
                reservation = inventoryPort.reserve(command);
            } catch (RuntimeException sendFailure) {
                reservation = inventoryPort.getReservation(order.getReservationNo());
            }
        } catch (RuntimeException exception) {
            scheduleRecovery(orderNo, exception);
            return;
        }

        Long resolvedWarehouseId = warehouseId;
        ReservationSnapshot resolvedReservation = reservation;
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity locked = requireLockedOrder(orderNo);
            if (!OrderStatus.PENDING_STOCK.name().equals(locked.getStatus())) {
                return;
            }
            locked.setWarehouseId(resolvedWarehouseId);
            locked.setNextRecoveryAt(null);
            locked.setLastError(null);
            locked.setRecoveryAttempts(0);
            if ("RESERVED".equals(resolvedReservation.status())) {
                transition(locked, OrderStatus.PENDING_PAYMENT, "RESERVE_STOCK", null,
                        "SYSTEM", "trade-service", "OrderAwaitingPayment");
            } else if (List.of("REJECTED", "RELEASED", "EXPIRED").contains(resolvedReservation.status())) {
                locked.setCloseReason("OUT_OF_STOCK");
                transition(locked, OrderStatus.CLOSED, "REJECT_STOCK", "OUT_OF_STOCK",
                        "SYSTEM", "inventory-service", "OrderClosed");
            } else {
                scheduleRecoveryLocked(locked, "Unexpected inventory status: " + resolvedReservation.status());
            }
        });
    }

    private void beginCancellation(
            String orderNo,
            Long ownerId,
            String reason,
            String operatorType,
            String operatorId,
            boolean terminalNoOp) {
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity order = requireLockedOrder(orderNo);
            if (ownerId != null) {
                requireOwner(order, ownerId);
            }
            if (OrderStatus.CANCELING.name().equals(order.getStatus())
                    || OrderStatus.CANCELED.name().equals(order.getStatus())) {
                return;
            }
            if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                if (terminalNoOp && (OrderStatus.CLOSED.name().equals(order.getStatus())
                        || OrderStatus.PAID.name().equals(order.getStatus()))) {
                    return;
                }
                throw new TradeException(TradeError.INVALID_STATE);
            }
            order.setCloseReason(reason);
            order.setNextRecoveryAt(clock.instant());
            transition(order, OrderStatus.CANCELING, "REQUEST_CANCEL", reason,
                    operatorType, operatorId, "OrderCancellationRequested");
        });
    }

    private void progressCancellation(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        if (!OrderStatus.CANCELING.name().equals(order.getStatus())) {
            return;
        }
        ReservationSnapshot reservation;
        try {
            try {
                reservation = inventoryPort.release(order.getReservationNo());
            } catch (RuntimeException sendFailure) {
                reservation = inventoryPort.getReservation(order.getReservationNo());
            }
        } catch (RuntimeException exception) {
            scheduleRecovery(orderNo, exception);
            return;
        }
        if (!List.of("RELEASED", "EXPIRED", "REJECTED").contains(reservation.status())) {
            scheduleRecovery(orderNo, new IllegalStateException(
                    "Unexpected inventory status during cancellation: " + reservation.status()));
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity locked = requireLockedOrder(orderNo);
            if (!OrderStatus.CANCELING.name().equals(locked.getStatus())) {
                return;
            }
            locked.setNextRecoveryAt(null);
            locked.setLastError(null);
            locked.setRecoveryAttempts(0);
            transition(locked, OrderStatus.CANCELED, "COMPLETE_CANCEL", locked.getCloseReason(),
                    "SYSTEM", "inventory-service", "OrderCanceled");
        });
    }

    private void scheduleRecovery(String orderNo, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity order = requireLockedOrder(orderNo);
            if (OrderStatus.PENDING_STOCK.name().equals(order.getStatus())
                    || OrderStatus.CANCELING.name().equals(order.getStatus())) {
                scheduleRecoveryLocked(order, conciseError(exception));
            }
        });
    }

    private void scheduleRecoveryLocked(TradeOrderEntity order, String error) {
        int attempts = order.getRecoveryAttempts() + 1;
        long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
        order.setRecoveryAttempts(attempts);
        order.setNextRecoveryAt(clock.instant().plusSeconds(delaySeconds));
        order.setLastError(error.length() <= 500 ? error : error.substring(0, 500));
        order.setUpdatedAt(clock.instant());
        requireUpdated(orderMapper.updateById(order));
    }

    private void transition(
            TradeOrderEntity order,
            OrderStatus target,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            String eventType) {
        String from = order.getStatus();
        Instant now = clock.instant();
        order.setStatus(target.name());
        order.setUpdatedAt(now);
        requireUpdated(orderMapper.updateById(order));
        appendHistory(order, from, target.name(), command, reason, operatorType, operatorId, now);
        appendOrderEvent(order, eventType, now);
    }

    private void insertOrderItems(Long orderId, List<ResolvedLine> snapshots, Instant now) {
        for (int index = 0; index < snapshots.size(); index++) {
            ResolvedLine snapshot = snapshots.get(index);
            OrderItemEntity item = new OrderItemEntity();
            item.setId(IdWorker.getId());
            item.setOrderId(orderId);
            item.setLineNo(index + 1);
            item.setProductId(snapshot.productId());
            item.setSkuId(snapshot.skuId());
            item.setProductTitle(snapshot.productTitle());
            item.setSkuCode(snapshot.skuCode());
            item.setSkuName(snapshot.skuName());
            item.setSpecJson(snapshot.specJson());
            item.setImageObjectKey(snapshot.imageObjectKey());
            item.setUnitPrice(snapshot.unitPrice());
            item.setQuantity(snapshot.quantity());
            item.setLineAmount(snapshot.lineAmount());
            item.setDiscountAmount(BigDecimal.ZERO.setScale(2));
            item.setPayableAmount(snapshot.lineAmount());
            item.setCreatedAt(now);
            itemMapper.insert(item);
        }
    }

    private void insertBenefitSelections(Long orderId, List<String> benefitNos, Instant now) {
        for (String benefitNo : benefitNos) {
            OrderBenefitSelectionEntity selection = new OrderBenefitSelectionEntity();
            selection.setId(IdWorker.getId());
            selection.setOrderId(orderId);
            selection.setBenefitNo(benefitNo);
            selection.setCreatedAt(now);
            benefitSelectionMapper.insert(selection);
        }
    }

    private void insertAddressSnapshot(Long orderId, AddressSnapshot snapshot, Instant now) {
        OrderAddressSnapshotEntity address = new OrderAddressSnapshotEntity();
        address.setId(IdWorker.getId());
        address.setOrderId(orderId);
        address.setSourceAddressId(snapshot.addressId());
        address.setRecipientName(snapshot.recipientName());
        address.setPhone(snapshot.phone());
        address.setProvince(snapshot.province());
        address.setProvinceCode(snapshot.provinceCode());
        address.setCity(snapshot.city());
        address.setCityCode(snapshot.cityCode());
        address.setDistrict(snapshot.district());
        address.setDistrictCode(snapshot.districtCode());
        address.setDetailAddress(snapshot.detailAddress());
        address.setPostalCode(snapshot.postalCode());
        address.setCreatedAt(now);
        addressSnapshotMapper.insert(address);
    }

    private void appendHistory(
            TradeOrderEntity order,
            String from,
            String to,
            String command,
            String reason,
            String operatorType,
            String operatorId,
            Instant now) {
        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity();
        history.setId(IdWorker.getId());
        history.setOrderId(order.getId());
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setCommand(command);
        history.setReason(reason);
        history.setOperatorType(operatorType);
        history.setOperatorId(operatorId);
        history.setCreatedAt(now);
        historyMapper.insert(history);
    }

    private void appendOrderEvent(TradeOrderEntity order, String eventType, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        payload.put("aggregateType", "TradeOrder");
        payload.put("aggregateId", order.getOrderNo());
        payload.put("aggregateVersion", order.getVersion());
        payload.put("occurredAt", now);
        payload.put("producer", "trade-service");
        payload.put("traceId", MDC.get("traceId"));
        payload.put("payloadVersion", 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("userId", order.getUserId());
        data.put("reservationNo", order.getReservationNo());
        data.put("status", order.getStatus());
        data.put("totalAmount", order.getTotalAmount());
        data.put("closeReason", order.getCloseReason());
        data.put("paymentDeadline", order.getPaymentDeadline());
        if ("OrderPaid".equals(eventType)) {
            data.put("deliveryAddress", addressView(orderAddress(order.getId())));
        }
        payload.put("payload", data);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("TradeOrder");
        event.setAggregateId(order.getOrderNo());
        event.setAggregateVersion(order.getVersion());
        event.setPayload(writeJson(payload));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private String requestHash(
            AddressSnapshot address,
            List<ResolvedLine> snapshots,
            List<String> benefitNos) {
        String canonical = properties.defaultWarehouseCode() + "|" + writeJson(address) + "|" + snapshots.stream()
                .map(item -> item.productId() + ":" + item.skuId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow() + "|" + String.join(",", benefitNos);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private TradeOrderEntity requireOrder(String orderNo) {
        TradeOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<TradeOrderEntity>()
                .eq(TradeOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return order;
    }

    private TradeOrderEntity requireLockedOrder(String orderNo) {
        TradeOrderEntity order = orderMapper.selectForUpdate(orderNo);
        if (order == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return order;
    }

    private void requireOwner(TradeOrderEntity order, Long userId) {
        if (!order.getUserId().equals(userId)) {
            throw new TradeException(TradeError.FORBIDDEN);
        }
    }

    private List<OrderItemEntity> orderItems(Long orderId) {
        return itemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                .eq(OrderItemEntity::getOrderId, orderId)
                .orderByAsc(OrderItemEntity::getLineNo));
    }

    private List<String> selectedBenefits(Long orderId) {
        return benefitSelectionMapper.selectList(new LambdaQueryWrapper<OrderBenefitSelectionEntity>()
                        .eq(OrderBenefitSelectionEntity::getOrderId, orderId)
                        .orderByAsc(OrderBenefitSelectionEntity::getBenefitNo))
                .stream().map(OrderBenefitSelectionEntity::getBenefitNo).toList();
    }

    private OrderAddressSnapshotEntity orderAddress(Long orderId) {
        OrderAddressSnapshotEntity address = addressSnapshotMapper.selectOne(
                new LambdaQueryWrapper<OrderAddressSnapshotEntity>()
                        .eq(OrderAddressSnapshotEntity::getOrderId, orderId));
        if (address == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return address;
    }

    private AddressSnapshotView addressView(OrderAddressSnapshotEntity address) {
        return new AddressSnapshotView(address.getSourceAddressId(), address.getRecipientName(), address.getPhone(),
                address.getProvince(), address.getProvinceCode(), address.getCity(), address.getCityCode(),
                address.getDistrict(), address.getDistrictCode(), address.getDetailAddress(), address.getPostalCode());
    }

    private OrderView view(TradeOrderEntity order) {
        List<OrderItemView> items = orderItems(order.getId()).stream().map(item -> new OrderItemView(
                item.getLineNo(), item.getProductId(), item.getSkuId(), item.getProductTitle(), item.getSkuCode(),
                item.getSkuName(), item.getSpecJson(), item.getImageObjectKey(), item.getUnitPrice(),
                item.getQuantity(), item.getLineAmount(), item.getDiscountAmount(), item.getPayableAmount())).toList();
        return new OrderView(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                priceSnapshotView(order.getId()), order.getPaymentDeadline(), order.getCloseReason(),
                addressView(orderAddress(order.getId())),
                items, order.getVersion(),
                order.getCreatedAt(), order.getUpdatedAt());
    }

    private PriceSnapshotView priceSnapshotView(Long orderId) {
        OrderPriceSnapshotEntity snapshot = priceSnapshotMapper.selectOne(
                new LambdaQueryWrapper<OrderPriceSnapshotEntity>()
                        .eq(OrderPriceSnapshotEntity::getOrderId, orderId));
        if (snapshot == null) {
            return null;
        }
        List<DiscountAllocationView> allocations = discountAllocationMapper.selectList(
                        new LambdaQueryWrapper<OrderDiscountAllocationEntity>()
                                .eq(OrderDiscountAllocationEntity::getOrderId, orderId)
                                .orderByAsc(OrderDiscountAllocationEntity::getLineNo)
                                .orderByAsc(OrderDiscountAllocationEntity::getId))
                .stream().map(allocation -> new DiscountAllocationView(
                        allocation.getLineNo(), allocation.getSkuId(), allocation.getBenefitNo(),
                        allocation.getRuleCode(), allocation.getBenefitType(), allocation.getDiscountAmount()))
                .toList();
        return new PriceSnapshotView(snapshot.getMarketingLockNo(), snapshot.getOriginalAmount(),
                snapshot.getCouponDiscount(), snapshot.getRedPacketDiscount(), snapshot.getSubsidyDiscount(),
                snapshot.getDiscountAmount(), snapshot.getPayableAmount(), snapshot.getPricingVersion(), allocations);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize trade event", exception);
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
        }
    }

    private String conciseError(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record OrderClaim(String orderNo, boolean created) {
    }
}
