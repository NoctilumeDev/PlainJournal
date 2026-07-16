package com.ecommerce.payment.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.domain.OutboxStatus;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.domain.RefundStatus;
import com.ecommerce.payment.infrastructure.config.MockChannelProperties;
import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundCallbackLogEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundTransactionEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentOrderMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundCallbackLogMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundOrderMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundTransactionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RefundService {

    public static final String REFUND_REQUESTED_CONSUMER_GROUP = "payment-refund-requested-v1";
    private static final String MOCK_CHANNEL = "MOCK";

    private final RefundOrderMapper refundMapper;
    private final RefundTransactionMapper transactionMapper;
    private final RefundCallbackLogMapper callbackMapper;
    private final PaymentOrderMapper paymentMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final MockChannelProperties channelProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RefundService(
            RefundOrderMapper refundMapper,
            RefundTransactionMapper transactionMapper,
            RefundCallbackLogMapper callbackMapper,
            PaymentOrderMapper paymentMapper,
            ConsumedEventMapper consumedEventMapper,
            OutboxEventMapper outboxMapper,
            MockChannelProperties channelProperties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.refundMapper = refundMapper;
        this.transactionMapper = transactionMapper;
        this.callbackMapper = callbackMapper;
        this.paymentMapper = paymentMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.outboxMapper = outboxMapper;
        this.channelProperties = channelProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public RefundView createFromRefundRequested(RefundRequestedCommand command) {
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(), REFUND_REQUESTED_CONSUMER_GROUP, clock.instant()) != 1) {
                return view(requireByAfterSaleNo(command.afterSaleNo()));
            }
            PaymentOrderEntity payment = paymentMapper.selectByOrderForUpdate(command.orderNo());
            if (payment == null) {
                throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
            }
            if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())
                    || !payment.getUserId().equals(command.userId())) {
                throw new PaymentException(PaymentError.INVALID_STATE);
            }
            if (payment.getAmount().compareTo(command.amount()) != 0) {
                throw new PaymentException(PaymentError.AMOUNT_MISMATCH);
            }
            String requestHash = sha256(String.join("|", command.afterSaleNo(), command.orderNo(),
                    command.userId().toString(), command.amount().stripTrailingZeros().toPlainString()));
            Instant now = clock.instant();
            long id = IdWorker.getId();
            RefundOrderEntity candidate = new RefundOrderEntity();
            candidate.setId(id);
            candidate.setRefundNo("RF" + id);
            candidate.setAfterSaleNo(command.afterSaleNo());
            candidate.setOrderNo(command.orderNo());
            candidate.setPaymentId(payment.getId());
            candidate.setPaymentNo(payment.getPaymentNo());
            candidate.setUserId(command.userId());
            candidate.setRequestHash(requestHash);
            candidate.setChannel(payment.getChannel());
            candidate.setStatus(RefundStatus.PROCESSING.name());
            candidate.setAmount(command.amount());
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            refundMapper.insertIfAbsent(candidate);

            RefundOrderEntity refund = refundMapper.selectByAfterSaleNoForUpdate(command.afterSaleNo());
            if (refund == null) {
                if (refundMapper.selectByPaymentIdForUpdate(payment.getId()) != null) {
                    throw new PaymentException(PaymentError.INVALID_STATE);
                }
                throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
            }
            if (!constantEquals(refund.getRequestHash(), requestHash)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            return view(refund);
        }));
    }

    public RefundView getForUser(Long userId, String refundNo) {
        RefundOrderEntity refund = requireRefund(refundNo);
        if (!refund.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.FORBIDDEN);
        }
        return view(refund);
    }

    public RefundView processMockCallback(RefundCallbackCommand command) {
        Instant receivedAt = clock.instant();
        PaymentError preflightError = preflightError(command, receivedAt);
        if (preflightError != null) {
            recordRejectedCallback(command, preflightError, false, receivedAt);
            throw new PaymentException(preflightError);
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(
                    ignored -> processValidCallback(command, receivedAt)));
        } catch (PaymentException exception) {
            recordRejectedCallback(command, exception.error(), true, receivedAt);
            throw exception;
        }
    }

    private RefundView processValidCallback(RefundCallbackCommand command, Instant receivedAt) {
        String requestHash = callbackHash(command);
        RefundCallbackLogEntity candidate = callbackLog(
                command, requestHash, true, "RECEIVED", null, receivedAt);
        callbackMapper.insertIfAbsent(candidate);
        RefundCallbackLogEntity callback = callbackMapper.selectForUpdate(MOCK_CHANNEL, command.externalEventId());
        if (callback == null) {
            throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
        }
        if (!constantEquals(callback.getRequestHash(), requestHash)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        if ("PROCESSED".equals(callback.getProcessingStatus())) {
            return view(requireRefund(command.refundNo()));
        }
        if (!Boolean.TRUE.equals(callback.getSignatureValid())) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }

        RefundOrderEntity refund = refundMapper.selectByRefundNoForUpdate(command.refundNo());
        if (refund == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        if (refund.getAmount().compareTo(command.amount()) != 0) {
            throw new PaymentException(PaymentError.AMOUNT_MISMATCH);
        }
        if (!MOCK_CHANNEL.equals(refund.getChannel())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        if ("SUCCESS".equals(command.status())) {
            applySuccess(refund, command, receivedAt);
        } else if ("FAILED".equals(command.status())) {
            applyFailure(refund, command, receivedAt);
        } else {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        callback.setProcessingStatus("PROCESSED");
        callback.setProcessedAt(receivedAt);
        callback.setErrorMessage(null);
        callbackMapper.updateById(callback);
        return view(refund);
    }

    private void applySuccess(RefundOrderEntity refund, RefundCallbackCommand command, Instant now) {
        if (RefundStatus.SUCCESS.name().equals(refund.getStatus())) {
            return;
        }
        if (!java.util.List.of(RefundStatus.PROCESSING.name(), RefundStatus.FAILED.name())
                .contains(refund.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        refund.setStatus(RefundStatus.SUCCESS.name());
        refund.setChannelRefundNo(command.externalRefundNo());
        refund.setRefundedAt(now);
        refund.setUpdatedAt(now);
        requireUpdated(refundMapper.updateById(refund));
        insertTransaction(refund, command, "SUCCESS", now);
        appendRefundEvent(refund, "RefundSucceeded", now);
    }

    private void applyFailure(RefundOrderEntity refund, RefundCallbackCommand command, Instant now) {
        if (RefundStatus.FAILED.name().equals(refund.getStatus())) {
            return;
        }
        if (!RefundStatus.PROCESSING.name().equals(refund.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        refund.setStatus(RefundStatus.FAILED.name());
        refund.setChannelRefundNo(command.externalRefundNo());
        refund.setUpdatedAt(now);
        requireUpdated(refundMapper.updateById(refund));
        insertTransaction(refund, command, "FAILED", now);
        appendRefundEvent(refund, "RefundFailed", now);
    }

    private void insertTransaction(
            RefundOrderEntity refund,
            RefundCallbackCommand command,
            String status,
            Instant now) {
        RefundTransactionEntity transaction = new RefundTransactionEntity();
        transaction.setId(IdWorker.getId());
        transaction.setRefundId(refund.getId());
        transaction.setChannel(MOCK_CHANNEL);
        transaction.setChannelRefundNo(command.externalRefundNo());
        transaction.setAmount(command.amount());
        transaction.setStatus(status);
        transaction.setCreatedAt(now);
        transactionMapper.insert(transaction);
    }

    private void appendRefundEvent(RefundOrderEntity refund, String eventType, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundNo", refund.getRefundNo());
        payload.put("afterSaleNo", refund.getAfterSaleNo());
        payload.put("orderNo", refund.getOrderNo());
        payload.put("paymentNo", refund.getPaymentNo());
        payload.put("userId", refund.getUserId());
        payload.put("amount", refund.getAmount());
        payload.put("channel", refund.getChannel());
        payload.put("channelRefundNo", refund.getChannelRefundNo());
        payload.put("status", refund.getStatus());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "RefundOrder");
        envelope.put("aggregateId", refund.getRefundNo());
        envelope.put("aggregateVersion", refund.getVersion());
        envelope.put("occurredAt", now);
        envelope.put("producer", "payment-service");
        envelope.put("traceId", MDC.get("traceId"));
        envelope.put("payloadVersion", 1);
        envelope.put("payload", payload);

        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(eventId);
        event.setEventType(eventType);
        event.setAggregateType("RefundOrder");
        event.setAggregateId(refund.getRefundNo());
        event.setAggregateVersion(refund.getVersion());
        event.setPayload(writeJson(envelope));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private PaymentError preflightError(RefundCallbackCommand command, Instant now) {
        long skew = Math.abs(now.getEpochSecond() - command.timestamp());
        if (skew > channelProperties.callbackMaxSkew().toSeconds()) {
            return PaymentError.CALLBACK_EXPIRED;
        }
        if (!MockCallbackSignature.verify(command, channelProperties.callbackSecret())) {
            return PaymentError.INVALID_SIGNATURE;
        }
        return null;
    }

    private void recordRejectedCallback(
            RefundCallbackCommand command,
            PaymentError error,
            boolean signatureValid,
            Instant receivedAt) {
        transactionTemplate.executeWithoutResult(ignored -> callbackMapper.insertIfAbsent(callbackLog(
                command, callbackHash(command), signatureValid, "REJECTED", error.code(), receivedAt)));
    }

    private RefundCallbackLogEntity callbackLog(
            RefundCallbackCommand command,
            String requestHash,
            boolean signatureValid,
            String status,
            String error,
            Instant receivedAt) {
        RefundCallbackLogEntity callback = new RefundCallbackLogEntity();
        callback.setId(IdWorker.getId());
        callback.setChannel(MOCK_CHANNEL);
        callback.setExternalEventId(command.externalEventId());
        callback.setRefundNo(command.refundNo());
        callback.setRequestHash(requestHash);
        callback.setSignatureValid(signatureValid);
        callback.setProcessingStatus(status);
        callback.setRawPayload(command.rawPayload());
        callback.setErrorMessage(error);
        callback.setReceivedAt(receivedAt);
        return callback;
    }

    private RefundOrderEntity requireByAfterSaleNo(String afterSaleNo) {
        RefundOrderEntity refund = refundMapper.selectOne(new LambdaQueryWrapper<RefundOrderEntity>()
                .eq(RefundOrderEntity::getAfterSaleNo, afterSaleNo));
        if (refund == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return refund;
    }

    private RefundOrderEntity requireRefund(String refundNo) {
        RefundOrderEntity refund = refundMapper.selectOne(new LambdaQueryWrapper<RefundOrderEntity>()
                .eq(RefundOrderEntity::getRefundNo, refundNo));
        if (refund == null) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return refund;
    }

    private RefundView view(RefundOrderEntity refund) {
        return new RefundView(
                refund.getRefundNo(), refund.getAfterSaleNo(), refund.getOrderNo(), refund.getPaymentNo(),
                refund.getUserId(), refund.getChannel(), refund.getStatus(), refund.getAmount(),
                refund.getChannelRefundNo(), refund.getCreatedAt(), refund.getUpdatedAt(), refund.getRefundedAt());
    }

    private String callbackHash(RefundCallbackCommand command) {
        return sha256(MockCallbackSignature.canonical(command));
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
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
            throw new IllegalStateException("Refund event serialization failed", exception);
        }
    }
}
