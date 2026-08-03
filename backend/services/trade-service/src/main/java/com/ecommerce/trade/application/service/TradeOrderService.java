package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.platform.common.api.CursorPageResponse;
import com.ecommerce.platform.common.api.KeysetCursor;
import com.ecommerce.platform.common.api.PageResponse;
import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.AddressSnapshotView;
import com.ecommerce.trade.application.model.TradeModels.CreateOrderCommand;
import com.ecommerce.trade.application.model.TradeModels.FulfillmentEventCommand;
import com.ecommerce.trade.application.model.TradeModels.FlashSaleAdmissionAcceptedCommand;
import com.ecommerce.trade.application.model.TradeModels.DiscountAllocationView;
import com.ecommerce.trade.application.model.TradeModels.OrderItemView;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.application.model.TradeModels.PaymentContextView;
import com.ecommerce.trade.application.model.TradeModels.PaymentSucceededCommand;
import com.ecommerce.trade.application.model.TradeModels.PriceSnapshotView;
import com.ecommerce.trade.application.model.TradeModels.RefundEventCommand;
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
import com.ecommerce.trade.infrastructure.observability.InventoryReservationRecoveryObservability;
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
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

@Service
public class TradeOrderService {

    public static final String PAYMENT_CONSUMER_GROUP = "trade-payment-succeeded-v1";
    public static final String PAYMENT_EXCEPTION_REFUND_CONSUMER_GROUP =
            "trade-payment-exception-refund-v1";
    public static final String FULFILLMENT_CONSUMER_GROUP = "trade-fulfillment-events-v1";
    private static final long MAX_ORDER_QUANTITY = 1_000_000_000L;
    private static final long[] CONCURRENT_CREATE_OBSERVATION_DELAYS_MILLIS =
            {25, 50, 100, 200, 300, 325};

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
    private final InventoryReservationRecoveryObservability inventoryRecoveryObservability;
    private final DistributedIdGenerator distributedIdGenerator;
    private final TradeShardRouter shardRouter;

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
            InventoryReservationRecoveryObservability inventoryRecoveryObservability,
            DistributedIdGenerator distributedIdGenerator,
            TradeShardRouter shardRouter) {
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
        this.inventoryRecoveryObservability = inventoryRecoveryObservability;
        this.distributedIdGenerator = distributedIdGenerator;
        this.shardRouter = shardRouter;
    }

    public OrderView createOrder(CreateOrderCommand command) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(command.userId(), () -> createOrder(command));
        }
        String requestHash = requestHash(command);
        TradeOrderEntity existing = orderMapper.selectByIdempotency(
                command.userId(), command.idempotencyKey());
        if (existing != null) {
            requireRequestHash(existing, requestHash);
            return view(existing);
        }

        AddressSnapshot address;
        List<ResolvedLine> snapshots;
        try {
            address = addressPort.getAddress(command.userId(), command.addressId());
            snapshots = snapshotService.resolve(command.items());
        } catch (TradeException exception) {
            if (exception.error() != TradeError.REMOTE_DEPENDENCY_UNAVAILABLE) {
                throw exception;
            }
            return observeConcurrentCreateOrRethrow(command, requestHash, exception);
        }
        BigDecimal originalTotal = snapshots.stream()
                .map(ResolvedLine::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = orderMapper.currentTime();
        long id = distributedIdGenerator.nextId();
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
            candidate.setOrderSource("STANDARD");
            candidate.setStatus(OrderStatus.PENDING_STOCK.name());
            candidate.setOriginalAmount(originalTotal);
            candidate.setDiscountAmount(BigDecimal.ZERO.setScale(2));
            candidate.setTotalAmount(originalTotal);
            candidate.setPaymentDeadline(now.plus(properties.paymentTimeout()));
            candidate.setRecoveryAttempts(0);
            candidate.setNextRecoveryAt(now.plus(properties.recoveryLease()));
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            orderMapper.insertOrLockExisting(candidate);

            TradeOrderEntity order = orderMapper.selectByIdempotencyForUpdate(
                    command.userId(), command.idempotencyKey());
            if (order == null) {
                throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
            }
            requireRequestHash(order, requestHash);
            boolean created = candidate.getId().equals(order.getId());
            if (created) {
                insertAddressSnapshot(order.getId(), address, now);
                insertOrderItems(order.getId(), snapshots, now);
                insertBenefitSelections(order.getId(), command.benefitNos(), now);
                appendHistory(order, null, OrderStatus.PENDING_STOCK.name(), "CREATE_ORDER",
                        null, "CUSTOMER", command.userId().toString(), now);
                appendOrderEvent(order, "OrderCreated", now);
            }
            return new OrderClaim(order.getOrderNo(), created);
        }));

        if (claim.created()) {
            progressPricingAndStockReservation(claim.orderNo());
        }
        return getOrder(command.userId(), claim.orderNo());
    }

    public OrderView createFlashSaleOrder(FlashSaleAdmissionAcceptedCommand command) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(command.userId(), () -> createFlashSaleOrder(command));
        }
        validateFlashSaleCommand(command);
        String idempotencyKey = "flash-" + command.requestToken();
        String requestHash = flashSaleRequestHash(command);
        TradeOrderEntity existing = orderMapper.selectByIdempotency(command.userId(), idempotencyKey);
        if (existing != null) {
            requireFlashSaleOrder(existing, command.requestToken(), requestHash);
            if (OrderStatus.PENDING_STOCK.name().equals(existing.getStatus())) {
                recoverOrder(existing.getOrderNo());
            }
            return getOrder(command.userId(), existing.getOrderNo());
        }

        AddressSnapshot address = addressPort.getAddress(command.userId(), command.addressId());
        ResolvedLine catalogLine = snapshotService.resolveOne(command.productId(), command.skuId(), 1);
        ResolvedLine flashSaleLine = new ResolvedLine(
                catalogLine.productId(),
                catalogLine.skuId(),
                catalogLine.productTitle(),
                catalogLine.skuCode(),
                catalogLine.skuName(),
                catalogLine.specJson(),
                catalogLine.imageObjectKey(),
                command.salePrice().setScale(2, java.math.RoundingMode.UNNECESSARY),
                1);

        Instant now = orderMapper.currentTime();
        long id = distributedIdGenerator.nextId();
        String orderNo = "ORD" + id;
        String reservationNo = "RSV" + id;
        String pricingReference = "FSL-" + command.requestToken();
        OrderClaim claim = Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            TradeOrderEntity candidate = new TradeOrderEntity();
            candidate.setId(id);
            candidate.setOrderNo(orderNo);
            candidate.setUserId(command.userId());
            candidate.setIdempotencyKey(idempotencyKey);
            candidate.setRequestHash(requestHash);
            candidate.setReservationNo(reservationNo);
            candidate.setWarehouseCode(properties.defaultWarehouseCode());
            candidate.setOrderSource("FLASH_SALE");
            candidate.setSourceReference(command.requestToken());
            candidate.setStatus(OrderStatus.PENDING_STOCK.name());
            candidate.setOriginalAmount(command.salePrice());
            candidate.setDiscountAmount(BigDecimal.ZERO.setScale(2));
            candidate.setTotalAmount(command.salePrice());
            candidate.setMarketingLockNo(pricingReference);
            candidate.setPaymentDeadline(now.plus(properties.paymentTimeout()));
            candidate.setRecoveryAttempts(0);
            candidate.setNextRecoveryAt(now.plus(properties.recoveryLease()));
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            orderMapper.insertOrLockExisting(candidate);

            TradeOrderEntity order = orderMapper.selectByIdempotencyForUpdate(
                    command.userId(), idempotencyKey);
            if (order == null) {
                throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
            }
            requireFlashSaleOrder(order, command.requestToken(), requestHash);
            boolean created = candidate.getId().equals(order.getId());
            if (created) {
                insertAddressSnapshot(order.getId(), address, now);
                insertOrderItems(order.getId(), List.of(flashSaleLine), now);
                insertFlashSalePriceSnapshot(order.getId(), pricingReference, command.salePrice(), now);
                appendHistory(order, null, OrderStatus.PENDING_STOCK.name(), "CREATE_FLASH_SALE_ORDER",
                        command.activityNo(), "SYSTEM", "flash-sale-consumer", now);
                appendOrderEvent(order, "OrderCreated", now);
            }
            return new OrderClaim(order.getOrderNo(), created);
        }));

        progressStockReservation(claim.orderNo());
        return getOrder(command.userId(), claim.orderNo());
    }

    private OrderView observeConcurrentCreateOrRethrow(
            CreateOrderCommand command,
            String requestHash,
            TradeException originalFailure) {
        for (long delayMillis : CONCURRENT_CREATE_OBSERVATION_DELAYS_MILLIS) {
            TradeOrderEntity existing = orderMapper.selectByIdempotency(
                    command.userId(), command.idempotencyKey());
            if (existing != null) {
                requireRequestHash(existing, requestHash);
                return view(existing);
            }
            LockSupport.parkNanos(Duration.ofMillis(delayMillis).toNanos());
            if (Thread.currentThread().isInterrupted()) {
                throw originalFailure;
            }
        }
        TradeOrderEntity existing = orderMapper.selectByIdempotency(
                command.userId(), command.idempotencyKey());
        if (existing != null) {
            requireRequestHash(existing, requestHash);
            return view(existing);
        }
        throw originalFailure;
    }

    public OrderView getOrder(Long userId, String orderNo) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(userId, () -> getOrder(userId, orderNo));
        }
        TradeOrderEntity order = requireOrder(orderNo);
        requireOwner(order, userId);
        return view(order);
    }

    public OrderView getOrderByIdempotencyKey(Long userId, String idempotencyKey) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(
                    userId, () -> getOrderByIdempotencyKey(userId, idempotencyKey));
        }
        TradeOrderEntity order = orderMapper.selectByIdempotency(userId, idempotencyKey);
        if (order == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return view(order);
    }

    public PageResponse<OrderView> listOrders(Long userId, long page, long size) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(userId, () -> listOrders(userId, page, size));
        }
        Page<TradeOrderEntity> result = orderMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<TradeOrderEntity>()
                        .eq(TradeOrderEntity::getUserId, userId)
                        .orderByDesc(TradeOrderEntity::getCreatedAt)
                        .orderByDesc(TradeOrderEntity::getId));
        return new PageResponse<>(views(result.getRecords()), page, size, result.getTotal());
    }

    public CursorPageResponse<OrderView> listOrdersByCursor(
            Long userId,
            int size,
            String encodedCursor) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(
                    userId, () -> listOrdersByCursor(userId, size, encodedCursor));
        }
        KeysetCursor cursor = decodeCursor(encodedCursor);
        List<TradeOrderEntity> fetched = orderMapper.selectUserCursorPage(
                userId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(),
                size + 1);
        boolean hasMore = fetched.size() > size;
        List<TradeOrderEntity> orders = hasMore
                ? List.copyOf(fetched.subList(0, size))
                : List.copyOf(fetched);
        String nextCursor = hasMore
                ? new KeysetCursor(
                        orders.get(orders.size() - 1).getCreatedAt(),
                        orders.get(orders.size() - 1).getId()).encode()
                : null;
        return new CursorPageResponse<>(views(orders), nextCursor, hasMore);
    }

    public PaymentContextView getPaymentContext(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        return new PaymentContextView(
                order.getOrderNo(),
                order.getUserId(),
                order.getReservationNo(),
                order.getPaymentNo(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentDeadline());
    }

    public void applyPaymentSucceeded(PaymentSucceededCommand command) {
        if (!shardRouter.isRouted()) {
            shardRouter.runForUser(command.userId(), () -> applyPaymentSucceeded(command));
            return;
        }
        PaymentApplicationOutcome outcome = Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            String payloadFingerprint = PayloadFingerprint.of(
                    command.paymentNo(),
                    command.orderNo(),
                    command.userId(),
                    command.reservationNo(),
                    canonicalDecimal(command.amount()));
            if (!registerConsumed(
                    command.eventId(), PAYMENT_CONSUMER_GROUP,
                    command.userId(), payloadFingerprint)) {
                return PaymentApplicationOutcome.NONE;
            }
            TradeOrderEntity order = requireLockedOrder(command.orderNo());
            if (!order.getUserId().equals(command.userId())
                    || !order.getReservationNo().equals(command.reservationNo())
                    || order.getTotalAmount().compareTo(command.amount()) != 0) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            boolean referenceBound = bindPaymentReference(order, command.paymentNo());
            if (List.of(
                    OrderStatus.PAID.name(),
                    OrderStatus.FULFILLING.name(),
                    OrderStatus.SHIPPED.name(),
                    OrderStatus.COMPLETED.name(),
                    OrderStatus.PAYMENT_EXCEPTION.name()).contains(order.getStatus())) {
                persistReferenceBinding(order, referenceBound);
                return PaymentApplicationOutcome.NONE;
            }
            if (OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())) {
                persistReferenceBinding(order, referenceBound);
                return PaymentApplicationOutcome.CONFIRM_INVENTORY;
            }
            if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
                if (OrderStatus.CANCELING.name().equals(order.getStatus())) {
                    Instant now = orderMapper.currentTime();
                    order.setNextRecoveryAt(now);
                    order.setLastError("Late payment detected while inventory release is pending");
                    order.setUpdatedAt(now);
                    requireUpdated(orderMapper.updateById(order));
                    appendHistory(
                            order,
                            OrderStatus.CANCELING.name(),
                            OrderStatus.CANCELING.name(),
                            "LATE_PAYMENT_DETECTED",
                            command.paymentNo(),
                            "SYSTEM",
                            "payment-service",
                            now);
                    return PaymentApplicationOutcome.COMPLETE_CANCELLATION;
                }
                if (List.of(OrderStatus.CANCELED.name(), OrderStatus.CLOSED.name())
                        .contains(order.getStatus())) {
                    transition(order, OrderStatus.PAYMENT_EXCEPTION, "LATE_PAYMENT_DETECTED",
                            command.paymentNo(), "SYSTEM", "payment-service", "PaymentReviewRequired");
                    return PaymentApplicationOutcome.NONE;
                }
                throw new TradeException(TradeError.INVALID_STATE);
            }
            order.setNextRecoveryAt(orderMapper.currentTime().plus(properties.recoveryLease()));
            order.setLastError(null);
            transition(order, OrderStatus.PAYMENT_CONFIRMING, "PAYMENT_SUCCEEDED",
                    command.paymentNo(), "SYSTEM", "payment-service",
                    "PaymentInventoryConfirmationRequested");
            return PaymentApplicationOutcome.CONFIRM_INVENTORY;
        }));
        if (outcome == PaymentApplicationOutcome.CONFIRM_INVENTORY) {
            progressPaymentConfirmation(command.orderNo());
        } else if (outcome == PaymentApplicationOutcome.COMPLETE_CANCELLATION) {
            progressCancellation(command.orderNo());
        }
    }

    public void applyPaymentExceptionRefundEvent(RefundEventCommand command) {
        if (!shardRouter.isRouted()) {
            shardRouter.runForUser(
                    command.userId(),
                    () -> applyPaymentExceptionRefundEvent(command));
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            String payloadFingerprint = PayloadFingerprint.of(
                    command.eventType(),
                    command.refundNo(),
                    command.afterSaleNo(),
                    command.orderNo(),
                    command.paymentNo(),
                    command.userId(),
                    canonicalDecimal(command.amount()));
            if (!registerConsumed(
                    command.eventId(),
                    PAYMENT_EXCEPTION_REFUND_CONSUMER_GROUP,
                    command.userId(),
                    payloadFingerprint)) {
                return;
            }
            TradeOrderEntity order = requireLockedOrder(command.orderNo());
            if (!Objects.equals(order.getUserId(), command.userId())
                    || !Objects.equals(order.getPaymentNo(), command.paymentNo())
                    || order.getTotalAmount().compareTo(command.amount()) != 0
                    || !("PEX-" + order.getOrderNo()).equals(command.afterSaleNo())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            if (order.getExceptionRefundNo() != null
                    && !order.getExceptionRefundNo().equals(command.refundNo())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            order.setExceptionRefundNo(command.refundNo());
            if ("RefundSucceeded".equals(command.eventType())) {
                if (OrderStatus.CLOSED.name().equals(order.getStatus())) {
                    return;
                }
                if (!OrderStatus.PAYMENT_EXCEPTION.name().equals(order.getStatus())) {
                    throw new TradeException(TradeError.INVALID_STATE);
                }
                order.setNextRecoveryAt(null);
                order.setLastError(null);
                transition(order, OrderStatus.CLOSED, "PAYMENT_EXCEPTION_REFUNDED",
                        command.refundNo(), "SYSTEM", "payment-service", "OrderClosed");
            } else if ("RefundFailed".equals(command.eventType())) {
                if (OrderStatus.CLOSED.name().equals(order.getStatus())) {
                    return;
                }
                if (!OrderStatus.PAYMENT_EXCEPTION.name().equals(order.getStatus())) {
                    throw new TradeException(TradeError.INVALID_STATE);
                }
                Instant now = orderMapper.currentTime();
                order.setLastError(
                        "Exceptional-payment refund failed: " + command.refundNo());
                order.setUpdatedAt(now);
                requireUpdated(orderMapper.updateById(order));
                appendHistory(
                        order,
                        OrderStatus.PAYMENT_EXCEPTION.name(),
                        OrderStatus.PAYMENT_EXCEPTION.name(),
                        "PAYMENT_EXCEPTION_REFUND_FAILED",
                        command.refundNo(),
                        "SYSTEM",
                        "payment-service",
                        now);
            } else {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        });
    }

    public void applyFulfillmentEvent(FulfillmentEventCommand command) {
        if (!shardRouter.isRouted()) {
            shardRouter.runForUser(command.userId(), () -> applyFulfillmentEvent(command));
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            String payloadFingerprint = PayloadFingerprint.of(
                    command.eventType(),
                    command.fulfillmentNo(),
                    command.orderNo(),
                    command.userId());
            if (!registerConsumed(
                    command.eventId(), FULFILLMENT_CONSUMER_GROUP,
                    command.userId(), payloadFingerprint)) {
                return;
            }
            TradeOrderEntity order = requireLockedOrder(command.orderNo());
            if (!order.getUserId().equals(command.userId())) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            boolean referenceBound = bindFulfillmentReference(order, command.fulfillmentNo());
            switch (command.eventType()) {
                case "FulfillmentCreated" ->
                        applyFulfillmentCreated(order, command.fulfillmentNo(), referenceBound);
                case "ShipmentDispatched" ->
                        applyShipmentDispatched(order, command.fulfillmentNo(), referenceBound);
                case "ShipmentSigned" ->
                        applyShipmentSigned(order, command.fulfillmentNo(), referenceBound);
                default -> throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
        });
    }

    private boolean registerConsumed(
            String eventId,
            String consumerGroup,
            Long ownerUserId,
            String payloadFingerprint) {
        if (consumedEventMapper.insertIfAbsent(
                eventId, consumerGroup, ownerUserId, payloadFingerprint,
                orderMapper.currentTime()) == 1) {
            return true;
        }
        Long storedOwner = consumedEventMapper.selectOwnerUserId(eventId, consumerGroup);
        String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(eventId, consumerGroup);
        if (!Objects.equals(storedOwner, ownerUserId)
                || !PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
        return false;
    }

    private String canonicalDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private boolean bindPaymentReference(TradeOrderEntity order, String paymentNo) {
        if (order.getPaymentNo() != null) {
            if (!order.getPaymentNo().equals(paymentNo)) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            return false;
        }
        order.setPaymentNo(paymentNo);
        return true;
    }

    private boolean bindFulfillmentReference(TradeOrderEntity order, String fulfillmentNo) {
        if (order.getFulfillmentNo() != null) {
            if (!order.getFulfillmentNo().equals(fulfillmentNo)) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            return false;
        }
        order.setFulfillmentNo(fulfillmentNo);
        return true;
    }

    private void persistReferenceBinding(TradeOrderEntity order, boolean referenceBound) {
        if (!referenceBound) {
            return;
        }
        order.setUpdatedAt(orderMapper.currentTime());
        requireUpdated(orderMapper.updateById(order));
    }

    private void applyFulfillmentCreated(
            TradeOrderEntity order,
            String fulfillmentNo,
            boolean referenceBound) {
        if (List.of(OrderStatus.FULFILLING.name(), OrderStatus.SHIPPED.name(),
                OrderStatus.COMPLETED.name()).contains(order.getStatus())) {
            persistReferenceBinding(order, referenceBound);
            return;
        }
        if (!OrderStatus.PAID.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.FULFILLING, "FULFILLMENT_CREATED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderFulfilling");
    }

    private void applyShipmentDispatched(
            TradeOrderEntity order,
            String fulfillmentNo,
            boolean referenceBound) {
        if (List.of(OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name()).contains(order.getStatus())) {
            persistReferenceBinding(order, referenceBound);
            return;
        }
        if (!OrderStatus.FULFILLING.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.SHIPPED, "SHIPMENT_DISPATCHED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderShipped");
    }

    private void applyShipmentSigned(
            TradeOrderEntity order,
            String fulfillmentNo,
            boolean referenceBound) {
        if (OrderStatus.COMPLETED.name().equals(order.getStatus())) {
            persistReferenceBinding(order, referenceBound);
            return;
        }
        if (!OrderStatus.SHIPPED.name().equals(order.getStatus())) {
            throw new TradeException(TradeError.INVALID_STATE);
        }
        transition(order, OrderStatus.COMPLETED, "SHIPMENT_SIGNED", fulfillmentNo,
                "SYSTEM", "fulfillment-service", "OrderCompleted");
    }

    public OrderView cancelOrder(Long userId, String orderNo) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(userId, () -> cancelOrder(userId, orderNo));
        }
        beginCancellation(orderNo, userId, "USER_CANCELED", "CUSTOMER", userId.toString(), false);
        progressCancellation(orderNo);
        return getOrder(userId, orderNo);
    }

    public void recoverOrder(String orderNo) {
        if (!shardRouter.isRouted()) {
            TradeOrderEntity located = requireOrder(orderNo);
            shardRouter.runForUser(located.getUserId(), () -> recoverOrder(orderNo));
            return;
        }
        TradeOrderEntity order = requireOrder(orderNo);
        if (OrderStatus.PENDING_STOCK.name().equals(order.getStatus())) {
            progressPricingAndStockReservation(orderNo);
        } else if (OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())) {
            progressPaymentConfirmation(orderNo);
        } else if (OrderStatus.CANCELING.name().equals(order.getStatus())) {
            progressCancellation(orderNo);
        }
    }

    public void cancelTimedOutOrder(String orderNo) {
        if (!shardRouter.isRouted()) {
            TradeOrderEntity located = requireOrder(orderNo);
            shardRouter.runForUser(located.getUserId(), () -> cancelTimedOutOrder(orderNo));
            return;
        }
        beginCancellation(orderNo, null, "PAYMENT_TIMEOUT", "SYSTEM", "trade-recovery", true);
        progressCancellation(orderNo);
    }

    public List<String> findRecoverableOrderNumbers(int limit) {
        return orderMapper.selectRecoverableOrderNumbers(orderMapper.currentTime(), limit);
    }

    public List<String> findTimedOutOrderNumbers(int limit) {
        return orderMapper.selectTimedOutOrderNumbers(orderMapper.currentTime(), limit);
    }

    public boolean tryClaimRecovery(String orderNo, String owner) {
        if (!shardRouter.isRouted()) {
            TradeOrderEntity located = requireOrder(orderNo);
            return shardRouter.executeForUser(
                    located.getUserId(),
                    () -> tryClaimRecovery(orderNo, owner));
        }
        Instant now = orderMapper.currentTime();
        return orderMapper.claimRecovery(
                orderNo,
                owner,
                now,
                now.plus(properties.recoveryLease())) == 1;
    }

    public boolean releaseRecoveryClaim(String orderNo, String owner) {
        if (!shardRouter.isRouted()) {
            TradeOrderEntity located = requireOrder(orderNo);
            return shardRouter.executeForUser(
                    located.getUserId(),
                    () -> releaseRecoveryClaim(orderNo, owner));
        }
        return orderMapper.releaseRecoveryClaim(orderNo, owner) == 1;
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
            Instant now = orderMapper.currentTime();
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
            order.setNextRecoveryAt(orderMapper.currentTime().plus(properties.recoveryLease()));
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
                .collect(Collectors.toSet());
        if (!selected.equals(appliedNos) || appliedNos.size() != pricing.appliedBenefits().size()) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
        Map<Integer, OrderItemEntity> itemByLine = items.stream()
                .collect(Collectors.toMap(OrderItemEntity::getLineNo, item -> item));
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
        ReservationResolution resolution;
        try {
            warehouseId = inventoryPort.getWarehouse(order.getWarehouseCode()).id();
            ReservationCommand command = new ReservationCommand(
                    order.getReservationNo(), order.getOrderNo(), warehouseId,
                    order.getPaymentDeadline().plus(properties.reservationGrace()),
                    items.stream().map(item -> new ReservationLine(item.getSkuId(), item.getQuantity())).toList());
            resolution = reserveOrResolveUnknownResult(command);
        } catch (RuntimeException exception) {
            scheduleRecovery(orderNo, exception);
            return;
        }

        Long resolvedWarehouseId = warehouseId;
        ReservationSnapshot resolvedReservation = resolution.reservation();
        boolean resolvedAfterUnknownResult = resolution.resolvedAfterUnknownResult();
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
                transition(locked, OrderStatus.PENDING_PAYMENT,
                        resolvedAfterUnknownResult ? "RESOLVE_STOCK_RESULT" : "RESERVE_STOCK",
                        resolvedAfterUnknownResult ? "RESERVE_RESPONSE_UNKNOWN" : null,
                        "SYSTEM", "trade-service", "OrderAwaitingPayment");
            } else if (List.of("REJECTED", "RELEASED", "EXPIRED").contains(resolvedReservation.status())) {
                locked.setCloseReason("OUT_OF_STOCK");
                transition(locked, OrderStatus.CLOSED,
                        resolvedAfterUnknownResult ? "RESOLVE_STOCK_RESULT" : "REJECT_STOCK",
                        resolvedAfterUnknownResult ? "OUT_OF_STOCK_AFTER_RESPONSE_UNKNOWN" : "OUT_OF_STOCK",
                        "SYSTEM", "inventory-service", "OrderClosed");
            } else {
                scheduleRecoveryLocked(locked, "Unexpected inventory status: " + resolvedReservation.status());
            }
        });
    }

    private ReservationResolution reserveOrResolveUnknownResult(ReservationCommand command) {
        try {
            ReservationSnapshot reservation = inventoryPort.reserve(command);
            validateReservation(command, reservation);
            return new ReservationResolution(reservation, false);
        } catch (RuntimeException reserveFailure) {
            try {
                ReservationSnapshot reservation = inventoryPort.getReservation(command.reservationNo());
                validateReservation(command, reservation);
                inventoryRecoveryObservability.recordRecovered();
                return new ReservationResolution(reservation, true);
            } catch (RuntimeException queryFailure) {
                inventoryRecoveryObservability.recordUnresolved();
                IllegalStateException unresolved = new IllegalStateException(
                        "Inventory reservation result remains unknown after status query",
                        reserveFailure);
                unresolved.addSuppressed(queryFailure);
                throw unresolved;
            }
        }
    }

    private void progressPaymentConfirmation(String orderNo) {
        TradeOrderEntity order = requireOrder(orderNo);
        if (!OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())) {
            return;
        }
        ReservationSnapshot reservation;
        try {
            try {
                reservation = inventoryPort.confirm(order.getReservationNo());
            } catch (RuntimeException confirmFailure) {
                reservation = inventoryPort.getReservation(order.getReservationNo());
            }
            validatePaymentReservation(order, reservation);
        } catch (RuntimeException exception) {
            scheduleRecovery(orderNo, exception);
            return;
        }

        ReservationSnapshot resolved = reservation;
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity locked = requireLockedOrder(orderNo);
            if (!OrderStatus.PAYMENT_CONFIRMING.name().equals(locked.getStatus())) {
                return;
            }
            locked.setNextRecoveryAt(null);
            locked.setLastError(null);
            locked.setRecoveryAttempts(0);
            if ("CONFIRMED".equals(resolved.status())) {
                transition(locked, OrderStatus.PAID, "CONFIRM_PAID_INVENTORY",
                        locked.getPaymentNo(), "SYSTEM", "inventory-service", "OrderPaid");
            } else if (List.of("RELEASED", "EXPIRED", "REJECTED").contains(resolved.status())) {
                transition(locked, OrderStatus.PAYMENT_EXCEPTION,
                        "PAID_INVENTORY_UNAVAILABLE", resolved.status(),
                        "SYSTEM", "inventory-service", "PaymentReviewRequired");
            } else {
                scheduleRecoveryLocked(
                        locked,
                        "Unexpected inventory status during payment confirmation: "
                                + resolved.status());
            }
        });
    }

    private void validatePaymentReservation(
            TradeOrderEntity order,
            ReservationSnapshot reservation) {
        List<ReservationLine> expectedItems = orderItems(order.getId()).stream()
                .map(item -> new ReservationLine(item.getSkuId(), item.getQuantity()))
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        List<ReservationLine> actualItems = reservation.items().stream()
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        if (!Objects.equals(order.getReservationNo(), reservation.reservationNo())
                || !Objects.equals(order.getOrderNo(), reservation.orderNo())
                || !Objects.equals(order.getWarehouseId(), reservation.warehouseId())
                || !expectedItems.equals(actualItems)) {
            throw new IllegalStateException(
                    "Inventory reservation does not match the paid order");
        }
    }

    private void validateReservation(ReservationCommand command, ReservationSnapshot reservation) {
        List<ReservationLine> expectedItems = command.items().stream()
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        List<ReservationLine> actualItems = reservation.items().stream()
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        if (!command.reservationNo().equals(reservation.reservationNo())
                || !command.orderNo().equals(reservation.orderNo())
                || !command.warehouseId().equals(reservation.warehouseId())
                || !command.expiresAt().equals(reservation.expiresAt())
                || !expectedItems.equals(actualItems)) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
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
            order.setNextRecoveryAt(orderMapper.currentTime().plus(properties.recoveryLease()));
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
            validateCancellationReservation(order, reservation);
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
            if (locked.getPaymentNo() != null) {
                transition(locked, OrderStatus.PAYMENT_EXCEPTION, "LATE_PAYMENT_DETECTED",
                        locked.getPaymentNo(), "SYSTEM", "payment-service",
                        "PaymentReviewRequired");
            }
        });
    }

    private void validateCancellationReservation(
            TradeOrderEntity order,
            ReservationSnapshot reservation) {
        List<ReservationLine> expectedItems = orderItems(order.getId()).stream()
                .map(item -> new ReservationLine(item.getSkuId(), item.getQuantity()))
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        List<ReservationLine> actualItems = reservation.items().stream()
                .sorted(java.util.Comparator.comparing(ReservationLine::skuId))
                .toList();
        if (!Objects.equals(order.getReservationNo(), reservation.reservationNo())
                || !Objects.equals(order.getOrderNo(), reservation.orderNo())
                || !Objects.equals(order.getWarehouseId(), reservation.warehouseId())
                || !expectedItems.equals(actualItems)) {
            throw new IllegalStateException(
                    "Inventory reservation does not match the canceling order");
        }
    }

    private void scheduleRecovery(String orderNo, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(ignored -> {
            TradeOrderEntity order = requireLockedOrder(orderNo);
            if (OrderStatus.PENDING_STOCK.name().equals(order.getStatus())
                    || OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())
                    || OrderStatus.CANCELING.name().equals(order.getStatus())) {
                scheduleRecoveryLocked(order, conciseError(exception));
            }
        });
    }

    private void scheduleRecoveryLocked(TradeOrderEntity order, String error) {
        int attempts = order.getRecoveryAttempts() + 1;
        long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
        order.setRecoveryAttempts(attempts);
        order.setNextRecoveryAt(orderMapper.currentTime().plusSeconds(delaySeconds));
        order.setLastError(error.length() <= 500 ? error : error.substring(0, 500));
        order.setUpdatedAt(orderMapper.currentTime());
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
        Instant now = orderMapper.currentTime();
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

    private void insertFlashSalePriceSnapshot(
            Long orderId,
            String pricingReference,
            BigDecimal salePrice,
            Instant now) {
        OrderPriceSnapshotEntity snapshot = new OrderPriceSnapshotEntity();
        snapshot.setId(IdWorker.getId());
        snapshot.setOrderId(orderId);
        snapshot.setMarketingLockNo(pricingReference);
        snapshot.setOriginalAmount(salePrice);
        snapshot.setCouponDiscount(BigDecimal.ZERO.setScale(2));
        snapshot.setRedPacketDiscount(BigDecimal.ZERO.setScale(2));
        snapshot.setSubsidyDiscount(BigDecimal.ZERO.setScale(2));
        snapshot.setDiscountAmount(BigDecimal.ZERO.setScale(2));
        snapshot.setPayableAmount(salePrice);
        snapshot.setPricingVersion("flash-sale-v1");
        snapshot.setCreatedAt(now);
        priceSnapshotMapper.insert(snapshot);
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
        if (order.getPaymentNo() != null) {
            data.put("paymentNo", order.getPaymentNo());
        }
        if ("OrderPaid".equals(eventType)) {
            data.put("deliveryAddress", deliveryAddressEventPayload(orderAddress(order.getId())));
        }
        if ("OrderCompleted".equals(eventType)) {
            data.put("items", orderItems(order.getId()).stream()
                    .map(this::completedOrderItemEventPayload)
                    .toList());
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

    private Map<String, Object> completedOrderItemEventPayload(OrderItemEntity item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lineNo", item.getLineNo());
        payload.put("productId", item.getProductId());
        payload.put("skuId", item.getSkuId());
        payload.put("productTitle", item.getProductTitle());
        payload.put("skuCode", item.getSkuCode());
        payload.put("skuName", item.getSkuName());
        payload.put("specJson", item.getSpecJson());
        payload.put("imageObjectKey", item.getImageObjectKey());
        payload.put("quantity", item.getQuantity());
        payload.put("lineAmount", item.getLineAmount());
        payload.put("discountAmount", item.getDiscountAmount());
        payload.put("payableAmount", item.getPayableAmount());
        return payload;
    }

    private String requestHash(CreateOrderCommand command) {
        Map<OrderLineKey, Long> quantities = new LinkedHashMap<>();
        for (var item : command.items()) {
            if (item.productId() == null || item.skuId() == null || item.quantity() <= 0) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
            }
            try {
                quantities.merge(
                        new OrderLineKey(item.productId(), item.skuId()),
                        item.quantity(),
                        Math::addExact);
            } catch (ArithmeticException exception) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE, exception);
            }
        }
        if (quantities.isEmpty()
                || quantities.values().stream().anyMatch(quantity -> quantity > MAX_ORDER_QUANTITY)) {
            throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
        }
        String items = quantities.entrySet().stream()
                .sorted(Map.Entry.<OrderLineKey, Long>comparingByKey(
                        java.util.Comparator.comparing(OrderLineKey::productId)
                                .thenComparing(OrderLineKey::skuId)))
                .map(entry -> entry.getKey().productId() + ":" + entry.getKey().skuId()
                        + ":" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        String canonical = properties.defaultWarehouseCode()
                + "|" + command.addressId()
                + "|" + items
                + "|" + String.join(",", command.benefitNos());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String flashSaleRequestHash(FlashSaleAdmissionAcceptedCommand command) {
        String canonical = String.join("|",
                command.requestToken(),
                command.activityNo(),
                command.userId().toString(),
                command.addressId().toString(),
                command.productId().toString(),
                command.skuId().toString(),
                command.salePrice().toPlainString(),
                command.acceptedAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validateFlashSaleCommand(FlashSaleAdmissionAcceptedCommand command) {
        if (command == null
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

    private void requireFlashSaleOrder(
            TradeOrderEntity order,
            String requestToken,
            String requestHash) {
        requireRequestHash(order, requestHash);
        if (!"FLASH_SALE".equals(order.getOrderSource())
                || !requestToken.equals(order.getSourceReference())) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireRequestHash(TradeOrderEntity order, String requestHash) {
        if (!MessageDigest.isEqual(
                order.getRequestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
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
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
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

    private Map<String, Object> deliveryAddressEventPayload(OrderAddressSnapshotEntity address) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceAddressId", address.getSourceAddressId());
        payload.put("recipientName", address.getRecipientName());
        payload.put("phone", address.getPhone());
        payload.put("province", address.getProvince());
        payload.put("provinceCode", address.getProvinceCode());
        payload.put("city", address.getCity());
        payload.put("cityCode", address.getCityCode());
        payload.put("district", address.getDistrict());
        payload.put("districtCode", address.getDistrictCode());
        payload.put("detailAddress", address.getDetailAddress());
        payload.put("postalCode", address.getPostalCode());
        return payload;
    }

    private OrderView view(TradeOrderEntity order) {
        return view(
                order,
                orderItems(order.getId()),
                orderAddress(order.getId()),
                priceSnapshotMapper.selectOne(new LambdaQueryWrapper<OrderPriceSnapshotEntity>()
                        .eq(OrderPriceSnapshotEntity::getOrderId, order.getId())),
                discountAllocationMapper.selectList(
                        new LambdaQueryWrapper<OrderDiscountAllocationEntity>()
                                .eq(OrderDiscountAllocationEntity::getOrderId, order.getId())
                                .orderByAsc(OrderDiscountAllocationEntity::getLineNo)
                                .orderByAsc(OrderDiscountAllocationEntity::getId)));
    }

    private List<OrderView> views(List<TradeOrderEntity> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> orderIds = orders.stream().map(TradeOrderEntity::getId).collect(Collectors.toSet());
        Map<Long, List<OrderItemEntity>> itemsByOrder = itemMapper.selectList(
                        new LambdaQueryWrapper<OrderItemEntity>()
                                .in(OrderItemEntity::getOrderId, orderIds)
                                .orderByAsc(OrderItemEntity::getOrderId)
                                .orderByAsc(OrderItemEntity::getLineNo))
                .stream().collect(Collectors.groupingBy(OrderItemEntity::getOrderId));
        Map<Long, OrderAddressSnapshotEntity> addressesByOrder = addressSnapshotMapper.selectList(
                        new LambdaQueryWrapper<OrderAddressSnapshotEntity>()
                                .in(OrderAddressSnapshotEntity::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(
                        OrderAddressSnapshotEntity::getOrderId,
                        Function.identity()));
        Map<Long, OrderPriceSnapshotEntity> pricesByOrder = priceSnapshotMapper.selectList(
                        new LambdaQueryWrapper<OrderPriceSnapshotEntity>()
                                .in(OrderPriceSnapshotEntity::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(
                        OrderPriceSnapshotEntity::getOrderId,
                        Function.identity()));
        Map<Long, List<OrderDiscountAllocationEntity>> allocationsByOrder = discountAllocationMapper.selectList(
                        new LambdaQueryWrapper<OrderDiscountAllocationEntity>()
                                .in(OrderDiscountAllocationEntity::getOrderId, orderIds)
                                .orderByAsc(OrderDiscountAllocationEntity::getOrderId)
                                .orderByAsc(OrderDiscountAllocationEntity::getLineNo)
                                .orderByAsc(OrderDiscountAllocationEntity::getId))
                .stream().collect(Collectors.groupingBy(OrderDiscountAllocationEntity::getOrderId));
        return orders.stream().map(order -> view(
                        order,
                        itemsByOrder.getOrDefault(order.getId(), List.of()),
                        requireOrderAddress(addressesByOrder.get(order.getId())),
                        pricesByOrder.get(order.getId()),
                        allocationsByOrder.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    private OrderAddressSnapshotEntity requireOrderAddress(OrderAddressSnapshotEntity address) {
        if (address == null) {
            throw new TradeException(TradeError.RESOURCE_NOT_FOUND);
        }
        return address;
    }

    private OrderView view(
            TradeOrderEntity order,
            List<OrderItemEntity> itemEntities,
            OrderAddressSnapshotEntity address,
            OrderPriceSnapshotEntity priceSnapshot,
            List<OrderDiscountAllocationEntity> allocationEntities) {
        List<OrderItemView> items = itemEntities.stream().map(item -> new OrderItemView(
                item.getLineNo(), item.getProductId(), item.getSkuId(), item.getProductTitle(), item.getSkuCode(),
                item.getSkuName(), item.getSpecJson(), item.getImageObjectKey(), item.getUnitPrice(),
                item.getQuantity(), item.getLineAmount(), item.getDiscountAmount(), item.getPayableAmount())).toList();
        return new OrderView(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                priceSnapshotView(priceSnapshot, allocationEntities), order.getPaymentDeadline(), order.getCloseReason(),
                addressView(address),
                items, order.getVersion(),
                order.getCreatedAt(), order.getUpdatedAt());
    }

    private PriceSnapshotView priceSnapshotView(
            OrderPriceSnapshotEntity snapshot,
            List<OrderDiscountAllocationEntity> allocationEntities) {
        if (snapshot == null) {
            return null;
        }
        List<DiscountAllocationView> allocations = allocationEntities.stream()
                .map(allocation -> new DiscountAllocationView(
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

    private KeysetCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            return KeysetCursor.decode(encodedCursor);
        } catch (IllegalArgumentException exception) {
            throw new TradeException(TradeError.INVALID_CURSOR, exception);
        }
    }

    private record OrderClaim(String orderNo, boolean created) {
    }

    private record OrderLineKey(Long productId, Long skuId) {
    }

    private record ReservationResolution(
            ReservationSnapshot reservation,
            boolean resolvedAfterUnknownResult
    ) {
    }

    private enum PaymentApplicationOutcome {
        NONE,
        CONFIRM_INVENTORY,
        COMPLETE_CANCELLATION
    }
}
