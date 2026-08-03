package com.ecommerce.payment.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.CallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentView;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.port.TradePort.PaymentContext;
import com.ecommerce.payment.domain.OutboxStatus;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.infrastructure.config.MockChannelProperties;
import com.ecommerce.payment.infrastructure.persistence.entity.CallbackSecurityAuditEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentCallbackLogEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentTransactionEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.CallbackSecurityAuditMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentCallbackLogMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentOrderMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentTransactionMapper;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentService {

    private static final String MOCK_CHANNEL = "MOCK";

    private final PaymentOrderMapper paymentMapper;
    private final PaymentTransactionMapper transactionMapper;
    private final PaymentCallbackLogMapper callbackMapper;
    private final CallbackSecurityAuditMapper securityAuditMapper;
    private final OutboxEventMapper outboxMapper;
    private final TradePort tradePort;
    private final MockChannelProperties channelProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MessagingTracing messagingTracing;

    public PaymentService(
            PaymentOrderMapper paymentMapper,
            PaymentTransactionMapper transactionMapper,
            PaymentCallbackLogMapper callbackMapper,
            CallbackSecurityAuditMapper securityAuditMapper,
            OutboxEventMapper outboxMapper,
            TradePort tradePort,
            MockChannelProperties channelProperties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            MessagingTracing messagingTracing) {
        this.paymentMapper = paymentMapper;
        this.transactionMapper = transactionMapper;
        this.callbackMapper = callbackMapper;
        this.securityAuditMapper = securityAuditMapper;
        this.outboxMapper = outboxMapper;
        this.tradePort = tradePort;
        this.channelProperties = channelProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.messagingTracing = messagingTracing;
    }

    public PaymentView createPayment(CreatePaymentCommand command) {
        String channel = command.channel().toUpperCase(Locale.ROOT);
        if (!MOCK_CHANNEL.equals(channel)) {
            throw new PaymentException(PaymentError.UNSUPPORTED_CHANNEL);
        }
        String requestHash = sha256(command.orderNo() + "|" + channel);
        PaymentView existing = findStablePayment(
                command.userId(), command.idempotencyKey(), command.orderNo(), channel, requestHash);
        if (existing != null) {
            return existing;
        }
        PaymentContext context = tradePort.getPaymentContext(command.orderNo());
        if (!context.userId().equals(command.userId())) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        Instant now = paymentMapper.currentTime();
        if (!"PENDING_PAYMENT".equals(context.status())
                || !context.paymentDeadline().isAfter(now)) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        long id = IdWorker.getId();

        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            PaymentOrderEntity candidate = new PaymentOrderEntity();
            candidate.setId(id);
            candidate.setPaymentNo("PAY" + id);
            candidate.setOrderNo(context.orderNo());
            candidate.setUserId(command.userId());
            candidate.setReservationNo(context.reservationNo());
            candidate.setIdempotencyKey(command.idempotencyKey());
            candidate.setRequestHash(requestHash);
            candidate.setChannel(channel);
            candidate.setStatus(PaymentStatus.PROCESSING.name());
            candidate.setAmount(context.totalAmount());
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            paymentMapper.insertOrLockExisting(candidate);

            PaymentOrderEntity payment = paymentMapper.selectByIdempotencyForUpdate(
                    command.userId(), command.idempotencyKey());
            if (payment != null) {
                if (!constantEquals(payment.getRequestHash(), requestHash)) {
                    throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
                }
                return view(payment);
            }
            payment = paymentMapper.selectByOrderForUpdate(context.orderNo());
            if (payment == null) {
                throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
            }
            if (!payment.getUserId().equals(command.userId()) || !payment.getChannel().equals(channel)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            return view(payment);
        }));
    }

    public PaymentView getPayment(Long userId, String paymentNo) {
        PaymentOrderEntity payment = requirePayment(paymentNo);
        if (!payment.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return view(payment);
    }

    public PaymentView getPaymentByIdempotencyKey(Long userId, String idempotencyKey) {
        PaymentOrderEntity payment = paymentMapper.selectByIdempotency(userId, idempotencyKey);
        if (payment == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return view(payment);
    }

    public PaymentView getPaymentByOrder(Long userId, String orderNo) {
        PaymentOrderEntity payment = paymentMapper.selectByOrder(orderNo);
        if (payment == null || !payment.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return view(payment);
    }

    public PaymentView processMockCallback(CallbackCommand command) {
        Instant receivedAt = paymentMapper.currentTime();
        if (!MockCallbackSignature.verify(command, channelProperties.callbackSecret())) {
            recordUntrustedCallback(command, receivedAt);
            throw new PaymentException(PaymentError.INVALID_SIGNATURE);
        }
        if (callbackExpired(command.timestamp(), receivedAt)) {
            recordRejectedCallback(command, PaymentError.CALLBACK_EXPIRED, true, receivedAt);
            throw new PaymentException(PaymentError.CALLBACK_EXPIRED);
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(ignored -> processValidCallback(command, receivedAt)));
        } catch (PaymentException exception) {
            recordRejectedCallback(command, exception.error(), true, receivedAt);
            throw exception;
        }
    }

    private PaymentView processValidCallback(CallbackCommand command, Instant receivedAt) {
        String requestHash = callbackHash(command);
        PaymentCallbackLogEntity candidate = callbackLog(command, requestHash, true, "RECEIVED", null, receivedAt);
        callbackMapper.insertOrLockExisting(candidate);
        PaymentCallbackLogEntity callback = callbackMapper.selectForUpdate(MOCK_CHANNEL, command.externalEventId());
        if (callback == null) {
            throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
        }
        if (!constantEquals(callback.getRequestHash(), requestHash)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        if ("PROCESSED".equals(callback.getProcessingStatus())) {
            return view(requirePayment(command.paymentNo()));
        }
        if (!Boolean.TRUE.equals(callback.getSignatureValid())) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }

        PaymentOrderEntity payment = paymentMapper.selectByPaymentNoForUpdate(command.paymentNo());
        if (payment == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        if (payment.getAmount().compareTo(command.amount()) != 0) {
            throw new PaymentException(PaymentError.AMOUNT_MISMATCH);
        }
        if (!MOCK_CHANNEL.equals(payment.getChannel())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }

        if ("SUCCESS".equals(command.status())) {
            applySuccess(payment, command, receivedAt);
        } else if ("FAILED".equals(command.status())) {
            applyFailure(payment, command, receivedAt);
        } else {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        callback.setProcessingStatus("PROCESSED");
        callback.setProcessedAt(receivedAt);
        callback.setErrorMessage(null);
        requireUpdated(callbackMapper.updateById(callback));
        return view(payment);
    }

    private void applySuccess(PaymentOrderEntity payment, CallbackCommand command, Instant now) {
        if (PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
            requireSameChannelTransactionNo(payment, command);
            return;
        }
        if (!PaymentStatus.PROCESSING.name().equals(payment.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        payment.setStatus(PaymentStatus.SUCCESS.name());
        payment.setChannelTransactionNo(command.externalTransactionNo());
        payment.setPaidAt(now);
        payment.setUpdatedAt(now);
        requireUpdated(paymentMapper.updateById(payment));
        insertTransaction(payment, command, "SUCCESS", now);
        appendPaymentSucceeded(payment, now);
    }

    private void applyFailure(PaymentOrderEntity payment, CallbackCommand command, Instant now) {
        if (PaymentStatus.FAILED.name().equals(payment.getStatus())) {
            requireSameChannelTransactionNo(payment, command);
            return;
        }
        if (!PaymentStatus.PROCESSING.name().equals(payment.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        payment.setStatus(PaymentStatus.FAILED.name());
        payment.setChannelTransactionNo(command.externalTransactionNo());
        payment.setUpdatedAt(now);
        requireUpdated(paymentMapper.updateById(payment));
        insertTransaction(payment, command, "FAILED", now);
    }

    private void insertTransaction(
            PaymentOrderEntity payment,
            CallbackCommand command,
            String status,
            Instant now) {
        PaymentTransactionEntity existing = transactionMapper.selectByChannelTransactionNoForUpdate(
                MOCK_CHANNEL, command.externalTransactionNo());
        if (existing != null) {
            if (!existing.getPaymentId().equals(payment.getId())
                    || existing.getAmount().compareTo(command.amount()) != 0
                    || !existing.getStatus().equals(status)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        PaymentTransactionEntity transaction = new PaymentTransactionEntity();
        transaction.setId(IdWorker.getId());
        transaction.setPaymentId(payment.getId());
        transaction.setTransactionType("PAYMENT");
        transaction.setChannel(MOCK_CHANNEL);
        transaction.setChannelTransactionNo(command.externalTransactionNo());
        transaction.setAmount(command.amount());
        transaction.setStatus(status);
        transaction.setCreatedAt(now);
        transactionMapper.insert(transaction);
    }

    private void requireSameChannelTransactionNo(
            PaymentOrderEntity payment,
            CallbackCommand command) {
        if (!Objects.equals(
                payment.getChannelTransactionNo(), command.externalTransactionNo())) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void appendPaymentSucceeded(PaymentOrderEntity payment, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "PaymentSucceeded");
        envelope.put("aggregateType", "PaymentOrder");
        envelope.put("aggregateId", payment.getPaymentNo());
        envelope.put("aggregateVersion", payment.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "payment-service");
        envelope.put("traceId", messagingTracing.currentTraceId());
        envelope.put("traceContext", messagingTracing.capture());
        envelope.put("payloadVersion", 1);
        envelope.put("payload", Map.of(
                "paymentNo", payment.getPaymentNo(),
                "orderNo", payment.getOrderNo(),
                "userId", payment.getUserId(),
                "reservationNo", payment.getReservationNo(),
                "amount", payment.getAmount(),
                "channel", payment.getChannel(),
                "channelTransactionNo", payment.getChannelTransactionNo()
        ));

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType("PaymentSucceeded");
        event.setAggregateType("PaymentOrder");
        event.setAggregateId(payment.getPaymentNo());
        event.setAggregateVersion(payment.getVersion());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private boolean callbackExpired(long timestamp, Instant now) {
        try {
            return Math.abs(Math.subtractExact(now.getEpochSecond(), timestamp))
                    > channelProperties.callbackMaxSkew().toSeconds();
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private void recordUntrustedCallback(CallbackCommand command, Instant receivedAt) {
        CallbackSecurityAuditEntity audit = new CallbackSecurityAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCallbackType("PAYMENT");
        audit.setChannel(MOCK_CHANNEL);
        audit.setClaimedExternalEventId(command.externalEventId());
        audit.setReferenceNo(command.paymentNo());
        audit.setRequestHash(callbackHash(command));
        audit.setSignatureValid(false);
        audit.setErrorCode(PaymentError.INVALID_SIGNATURE.code());
        audit.setRawPayload(command.rawPayload());
        audit.setReceivedAt(receivedAt);
        transactionTemplate.executeWithoutResult(ignored -> securityAuditMapper.insert(audit));
    }

    private void recordRejectedCallback(
            CallbackCommand command,
            PaymentError error,
            boolean signatureValid,
            Instant receivedAt) {
        transactionTemplate.executeWithoutResult(ignored -> callbackMapper.insertOrLockExisting(callbackLog(
                command, callbackHash(command), signatureValid, "REJECTED", error.code(), receivedAt)));
    }

    private PaymentCallbackLogEntity callbackLog(
            CallbackCommand command,
            String requestHash,
            boolean signatureValid,
            String status,
            String error,
            Instant receivedAt) {
        PaymentCallbackLogEntity callback = new PaymentCallbackLogEntity();
        callback.setId(IdWorker.getId());
        callback.setChannel(MOCK_CHANNEL);
        callback.setExternalEventId(command.externalEventId());
        callback.setPaymentNo(command.paymentNo());
        callback.setRequestHash(requestHash);
        callback.setSignatureValid(signatureValid);
        callback.setProcessingStatus(status);
        callback.setRawPayload(command.rawPayload());
        callback.setErrorMessage(error);
        callback.setReceivedAt(receivedAt);
        return callback;
    }

    private String callbackHash(CallbackCommand command) {
        return sha256(MockCallbackSignature.canonical(command));
    }

    private PaymentOrderEntity requirePayment(String paymentNo) {
        PaymentOrderEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrderEntity>()
                .eq(PaymentOrderEntity::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return payment;
    }

    private PaymentView findStablePayment(
            Long userId,
            String idempotencyKey,
            String orderNo,
            String channel,
            String requestHash) {
        PaymentOrderEntity byIdempotency = paymentMapper.selectByIdempotency(userId, idempotencyKey);
        if (byIdempotency != null) {
            if (!constantEquals(byIdempotency.getRequestHash(), requestHash)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            return view(byIdempotency);
        }
        PaymentOrderEntity byOrder = paymentMapper.selectByOrder(orderNo);
        if (byOrder == null) {
            return null;
        }
        if (!byOrder.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        if (!byOrder.getChannel().equals(channel)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        return view(byOrder);
    }

    private PaymentView view(PaymentOrderEntity payment) {
        return new PaymentView(
                payment.getPaymentNo(), payment.getOrderNo(), payment.getChannel(), payment.getStatus(),
                payment.getAmount(), payment.getChannelTransactionNo(), payment.getPaidAt(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
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
            throw new IllegalStateException("Payment event serialization failed", exception);
        }
    }
}
