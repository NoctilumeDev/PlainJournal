package com.ecommerce.payment.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.payment.application.exception.PaymentError;
import com.ecommerce.payment.application.exception.PaymentException;
import com.ecommerce.payment.application.model.PaymentModels.CreatePaymentExceptionRefundCommand;
import com.ecommerce.payment.application.model.PaymentModels.PaymentExceptionRefundAuditView;
import com.ecommerce.payment.application.model.PaymentModels.RefundCallbackCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundDispatchRetryAuditView;
import com.ecommerce.payment.application.model.PaymentModels.RefundRequestedCommand;
import com.ecommerce.payment.application.model.PaymentModels.RefundView;
import com.ecommerce.payment.application.model.PaymentModels.RetryRefundDispatchCommand;
import com.ecommerce.payment.application.port.TradePort;
import com.ecommerce.payment.application.port.TradePort.PaymentContext;
import com.ecommerce.payment.domain.OutboxStatus;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.domain.RefundStatus;
import com.ecommerce.payment.infrastructure.config.MockChannelProperties;
import com.ecommerce.payment.infrastructure.persistence.entity.CallbackSecurityAuditEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.PaymentExceptionRefundAuditEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundCallbackLogEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundDispatchRetryAuditEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundTransactionEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.CallbackSecurityAuditMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentOrderMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.PaymentExceptionRefundAuditMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundCallbackLogMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundDispatchRetryAuditMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundOrderMapper;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundTransactionMapper;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
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
import java.util.List;
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
    private final CallbackSecurityAuditMapper securityAuditMapper;
    private final RefundDispatchRetryAuditMapper retryAuditMapper;
    private final PaymentExceptionRefundAuditMapper exceptionRefundAuditMapper;
    private final PaymentOrderMapper paymentMapper;
    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final MockChannelProperties channelProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MessagingTracing messagingTracing;
    private final TradePort tradePort;

    public RefundService(
            RefundOrderMapper refundMapper,
            RefundTransactionMapper transactionMapper,
            RefundCallbackLogMapper callbackMapper,
            CallbackSecurityAuditMapper securityAuditMapper,
            RefundDispatchRetryAuditMapper retryAuditMapper,
            PaymentExceptionRefundAuditMapper exceptionRefundAuditMapper,
            PaymentOrderMapper paymentMapper,
            ConsumedEventMapper consumedEventMapper,
            OutboxEventMapper outboxMapper,
            MockChannelProperties channelProperties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            MessagingTracing messagingTracing,
            TradePort tradePort) {
        this.refundMapper = refundMapper;
        this.transactionMapper = transactionMapper;
        this.callbackMapper = callbackMapper;
        this.securityAuditMapper = securityAuditMapper;
        this.retryAuditMapper = retryAuditMapper;
        this.exceptionRefundAuditMapper = exceptionRefundAuditMapper;
        this.paymentMapper = paymentMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.outboxMapper = outboxMapper;
        this.channelProperties = channelProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.messagingTracing = messagingTracing;
        this.tradePort = tradePort;
    }

    public RefundView createFromRefundRequested(RefundRequestedCommand command) {
        return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            String payloadFingerprint = PayloadFingerprint.of(command);
            if (consumedEventMapper.insertIfAbsent(
                    command.eventId(),
                    REFUND_REQUESTED_CONSUMER_GROUP,
                    payloadFingerprint,
                    refundMapper.currentTime()) != 1) {
                String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                        command.eventId(), REFUND_REQUESTED_CONSUMER_GROUP);
                if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                    throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
                }
                RefundOrderEntity repeated = requireByAfterSaleNo(command.afterSaleNo());
                if (!constantEquals(repeated.getRequestHash(), refundRequestHash(command))) {
                    throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
                }
                return view(repeated);
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
            String requestHash = refundRequestHash(command);
            Instant now = refundMapper.currentTime();
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
            candidate.setRequestStatus("PENDING");
            candidate.setRequestAttempts(0);
            candidate.setNextRequestAt(now);
            candidate.setVersion(0);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            refundMapper.insertOrLockExisting(candidate);

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

    public RefundView createPaymentExceptionRefund(
            CreatePaymentExceptionRefundCommand command) {
        String reason = command.reason().strip();
        String commandHash = paymentExceptionCommandHash(command, reason);
        PaymentExceptionRefundResult replayed = transactionTemplate.execute(ignored -> {
            PaymentExceptionRefundAuditEntity existing =
                    exceptionRefundAuditMapper.selectByCommandIdForUpdate(command.commandId());
            if (existing == null) {
                return null;
            }
            if (!constantEquals(existing.getRequestHash(), commandHash)) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            return replayPaymentExceptionRefund(existing);
        });
        if (replayed != null) {
            if (replayed.error() != null) {
                throw new PaymentException(replayed.error());
            }
            return replayed.refund();
        }

        PaymentOrderEntity snapshot = paymentMapper.selectByPaymentNo(command.paymentNo());
        PaymentContext context = snapshot == null
                ? null
                : tradePort.getPaymentContext(snapshot.getOrderNo());

        PaymentExceptionRefundResult result = Objects.requireNonNull(
                transactionTemplate.execute(ignored -> {
                    Instant now = refundMapper.currentTime();
                    PaymentExceptionRefundAuditEntity candidate =
                            paymentExceptionAudit(command, reason, commandHash, now);
                    exceptionRefundAuditMapper.insertOrLockExisting(candidate);
                    PaymentExceptionRefundAuditEntity audit =
                            exceptionRefundAuditMapper.selectByCommandIdForUpdate(
                                    command.commandId());
                    if (audit == null) {
                        throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
                    }
                    if (!constantEquals(audit.getRequestHash(), commandHash)) {
                        throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
                    }
                    if (!candidate.getId().equals(audit.getId())) {
                        return replayPaymentExceptionRefund(audit);
                    }

                    PaymentOrderEntity payment =
                            paymentMapper.selectByPaymentNoForUpdate(command.paymentNo());
                    PaymentError eligibilityError =
                            paymentExceptionEligibilityError(payment, context);
                    if (eligibilityError != null) {
                        audit.setOrderNo(payment == null ? null : payment.getOrderNo());
                        audit.setOutcome("REJECTED");
                        audit.setErrorCode(eligibilityError.code());
                        requireUpdated(exceptionRefundAuditMapper.updateById(audit));
                        return new PaymentExceptionRefundResult(null, eligibilityError);
                    }

                    String exceptionReference = paymentExceptionReference(payment.getOrderNo());
                    RefundOrderEntity existing =
                            refundMapper.selectByPaymentIdForUpdate(payment.getId());
                    if (existing != null) {
                        if (!exceptionReference.equals(existing.getAfterSaleNo())
                                || !payment.getPaymentNo().equals(existing.getPaymentNo())
                                || payment.getAmount().compareTo(existing.getAmount()) != 0) {
                            audit.setOrderNo(payment.getOrderNo());
                            audit.setOutcome("REJECTED");
                            audit.setErrorCode(
                                    PaymentError.PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED.code());
                            requireUpdated(exceptionRefundAuditMapper.updateById(audit));
                            return new PaymentExceptionRefundResult(
                                    null,
                                    PaymentError.PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED);
                        }
                        acceptPaymentExceptionAudit(audit, payment, existing);
                        return new PaymentExceptionRefundResult(view(existing), null);
                    }

                    long id = IdWorker.getId();
                    RefundOrderEntity refund = new RefundOrderEntity();
                    refund.setId(id);
                    refund.setRefundNo("RF" + id);
                    refund.setAfterSaleNo(exceptionReference);
                    refund.setOrderNo(payment.getOrderNo());
                    refund.setPaymentId(payment.getId());
                    refund.setPaymentNo(payment.getPaymentNo());
                    refund.setUserId(payment.getUserId());
                    refund.setRequestHash(paymentExceptionRefundHash(payment));
                    refund.setChannel(payment.getChannel());
                    refund.setStatus(RefundStatus.PROCESSING.name());
                    refund.setAmount(payment.getAmount());
                    refund.setRequestStatus("PENDING");
                    refund.setRequestAttempts(0);
                    refund.setNextRequestAt(now);
                    refund.setVersion(0);
                    refund.setCreatedAt(now);
                    refund.setUpdatedAt(now);
                    refundMapper.insertOrLockExisting(refund);

                    RefundOrderEntity persisted =
                            refundMapper.selectByPaymentIdForUpdate(payment.getId());
                    if (persisted == null
                            || !exceptionReference.equals(persisted.getAfterSaleNo())
                            || !constantEquals(
                            persisted.getRequestHash(),
                            paymentExceptionRefundHash(payment))) {
                        throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
                    }
                    acceptPaymentExceptionAudit(audit, payment, persisted);
                    return new PaymentExceptionRefundResult(view(persisted), null);
                }));
        if (result.error() != null) {
            throw new PaymentException(result.error());
        }
        return result.refund();
    }

    public List<PaymentExceptionRefundAuditView> listPaymentExceptionRefundAudits(
            String paymentNo,
            int limit) {
        return exceptionRefundAuditMapper.selectByPaymentNo(paymentNo, limit).stream()
                .map(this::paymentExceptionAuditView)
                .toList();
    }

    private String refundRequestHash(RefundRequestedCommand command) {
        return sha256(String.join("|", command.afterSaleNo(), command.orderNo(),
                command.userId().toString(), command.amount().stripTrailingZeros().toPlainString()));
    }

    public RefundView getForUser(Long userId, String refundNo) {
        RefundOrderEntity refund = requireRefund(refundNo);
        if (!refund.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return view(refund);
    }

    public RefundView getByAfterSaleNoForUser(Long userId, String afterSaleNo) {
        RefundOrderEntity refund = requireByAfterSaleNo(afterSaleNo);
        if (!refund.getUserId().equals(userId)) {
            throw new PaymentException(PaymentError.RESOURCE_NOT_FOUND);
        }
        return view(refund);
    }

    public RefundView retryDispatch(RetryRefundDispatchCommand command) {
        String reason = command.reason().strip();
        String requestHash = retryCommandHash(command, reason);
        RetryDispatchResult result = Objects.requireNonNull(transactionTemplate.execute(ignored -> {
            RefundDispatchRetryAuditEntity existing =
                    retryAuditMapper.selectByCommandIdForUpdate(command.commandId());
            if (existing != null) {
                return replayRetry(existing, requestHash);
            }

            RefundOrderEntity refund = refundMapper.selectByRefundNoForUpdate(command.refundNo());
            existing = retryAuditMapper.selectByCommandIdForUpdate(command.commandId());
            if (existing != null) {
                return replayRetry(existing, requestHash);
            }

            Instant now = refundMapper.currentTime();
            if (refund == null) {
                return rejectRetry(command, reason, requestHash, null,
                        PaymentError.RESOURCE_NOT_FOUND, now);
            }
            if (!isManualRetryAllowed(refund)) {
                return rejectRetry(command, reason, requestHash, refund,
                        PaymentError.REFUND_RETRY_NOT_ALLOWED, now);
            }

            RefundDispatchRetryAuditEntity audit = retryAudit(
                    command, reason, requestHash, refund, "ACCEPTED", null, now);
            requireUpdated(refundMapper.resetRequestForManualRetry(refund.getId(), now));
            RefundOrderEntity reset = refundMapper.selectByRefundNoForUpdate(command.refundNo());
            audit.setAfterRefundStatus(reset.getStatus());
            audit.setAfterRequestStatus(reset.getRequestStatus());
            audit.setAfterRequestAttempts(reset.getRequestAttempts());
            persistRetryAudit(audit, requestHash);
            return new RetryDispatchResult(view(reset), null);
        }));
        if (result.error() != null) {
            throw new PaymentException(result.error());
        }
        return result.refund();
    }

    public List<RefundDispatchRetryAuditView> listRetryAudits(String refundNo, int limit) {
        return retryAuditMapper.selectByRefundNo(refundNo, limit).stream()
                .map(this::retryAuditView)
                .toList();
    }

    public RefundView processMockCallback(RefundCallbackCommand command) {
        Instant receivedAt = refundMapper.currentTime();
        if (!MockCallbackSignature.verify(command, channelProperties.callbackSecret())) {
            recordUntrustedCallback(command, receivedAt);
            throw new PaymentException(PaymentError.INVALID_SIGNATURE);
        }
        if (callbackExpired(command.timestamp(), receivedAt)) {
            recordRejectedCallback(command, PaymentError.CALLBACK_EXPIRED, true, receivedAt);
            throw new PaymentException(PaymentError.CALLBACK_EXPIRED);
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
        callbackMapper.insertOrLockExisting(candidate);
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
        requireUpdated(callbackMapper.updateById(callback));
        return view(refund);
    }

    private void applySuccess(RefundOrderEntity refund, RefundCallbackCommand command, Instant now) {
        if (RefundStatus.SUCCESS.name().equals(refund.getStatus())) {
            requireSameChannelRefundNo(refund, command);
            return;
        }
        if (!List.of(RefundStatus.PROCESSING.name(), RefundStatus.FAILED.name())
                .contains(refund.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        refund.setStatus(RefundStatus.SUCCESS.name());
        refund.setChannelRefundNo(command.externalRefundNo());
        refund.setRefundedAt(now);
        refund.setUpdatedAt(now);
        requireUpdated(refundMapper.updateById(refund));
        acknowledgeRequest(refund, now);
        insertTransaction(refund, command, "SUCCESS", now);
        appendRefundEvent(refund, "RefundSucceeded", now);
    }

    private void applyFailure(RefundOrderEntity refund, RefundCallbackCommand command, Instant now) {
        if (RefundStatus.FAILED.name().equals(refund.getStatus())) {
            requireSameChannelRefundNo(refund, command);
            return;
        }
        if (!RefundStatus.PROCESSING.name().equals(refund.getStatus())) {
            throw new PaymentException(PaymentError.INVALID_STATE);
        }
        refund.setStatus(RefundStatus.FAILED.name());
        refund.setChannelRefundNo(command.externalRefundNo());
        refund.setUpdatedAt(now);
        requireUpdated(refundMapper.updateById(refund));
        acknowledgeRequest(refund, now);
        insertTransaction(refund, command, "FAILED", now);
        appendRefundEvent(refund, "RefundFailed", now);
    }

    private void insertTransaction(
            RefundOrderEntity refund,
            RefundCallbackCommand command,
            String status,
            Instant now) {
        RefundTransactionEntity existing = transactionMapper.selectByChannelRefundNoForUpdate(
                MOCK_CHANNEL, command.externalRefundNo());
        if (existing != null) {
            if (!existing.getRefundId().equals(refund.getId())
                    || existing.getAmount().compareTo(command.amount()) != 0) {
                throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
            }
            if (existing.getStatus().equals(status)) {
                return;
            }
            if (!"FAILED".equals(existing.getStatus()) || !"SUCCESS".equals(status)) {
                throw new PaymentException(PaymentError.INVALID_STATE);
            }
            existing.setStatus(status);
            requireUpdated(transactionMapper.updateById(existing));
            return;
        }
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
        envelope.put("traceId", messagingTracing.currentTraceId());
        envelope.put("traceContext", messagingTracing.capture());
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

    private boolean callbackExpired(long timestamp, Instant now) {
        try {
            return Math.abs(Math.subtractExact(now.getEpochSecond(), timestamp))
                    > channelProperties.callbackMaxSkew().toSeconds();
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private void recordUntrustedCallback(
            RefundCallbackCommand command,
            Instant receivedAt) {
        CallbackSecurityAuditEntity audit = new CallbackSecurityAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCallbackType("REFUND");
        audit.setChannel(MOCK_CHANNEL);
        audit.setClaimedExternalEventId(command.externalEventId());
        audit.setReferenceNo(command.refundNo());
        audit.setRequestHash(callbackHash(command));
        audit.setSignatureValid(false);
        audit.setErrorCode(PaymentError.INVALID_SIGNATURE.code());
        audit.setRawPayload(command.rawPayload());
        audit.setReceivedAt(receivedAt);
        transactionTemplate.executeWithoutResult(ignored -> securityAuditMapper.insert(audit));
    }

    private void recordRejectedCallback(
            RefundCallbackCommand command,
            PaymentError error,
            boolean signatureValid,
            Instant receivedAt) {
        transactionTemplate.executeWithoutResult(ignored -> callbackMapper.insertOrLockExisting(callbackLog(
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

    private void acknowledgeRequest(RefundOrderEntity refund, Instant now) {
        requireUpdated(refundMapper.markRequestAcknowledged(refund.getId(), now));
        refund.setRequestStatus("SENT");
        refund.setNextRequestAt(null);
        refund.setRequestClaimedAt(null);
        if (refund.getRequestSentAt() == null) {
            refund.setRequestSentAt(now);
        }
        refund.setLastRequestError(null);
    }

    private void requireSameChannelRefundNo(
            RefundOrderEntity refund,
            RefundCallbackCommand command) {
        if (!Objects.equals(refund.getChannelRefundNo(), command.externalRefundNo())) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
    }

    private RefundView view(RefundOrderEntity refund) {
        return new RefundView(
                refund.getRefundNo(), refund.getAfterSaleNo(), refund.getOrderNo(), refund.getPaymentNo(),
                refund.getUserId(), refund.getChannel(), refund.getStatus(), refund.getAmount(),
                refund.getChannelRefundNo(), refund.getRequestStatus(), refund.getRequestAttempts(),
                refund.getNextRequestAt(), refund.getRequestSentAt(), refund.getCreatedAt(),
                refund.getUpdatedAt(), refund.getRefundedAt());
    }

    private boolean isManualRetryAllowed(RefundOrderEntity refund) {
        return (RefundStatus.PROCESSING.name().equals(refund.getStatus())
                && "NEEDS_ATTENTION".equals(refund.getRequestStatus()))
                || (RefundStatus.FAILED.name().equals(refund.getStatus())
                && "SENT".equals(refund.getRequestStatus()));
    }

    private RetryDispatchResult rejectRetry(
            RetryRefundDispatchCommand command,
            String reason,
            String requestHash,
            RefundOrderEntity refund,
            PaymentError error,
            Instant now) {
        RefundDispatchRetryAuditEntity audit = retryAudit(
                command, reason, requestHash, refund, "REJECTED", error.name(), now);
        RefundDispatchRetryAuditEntity persisted = persistRetryAudit(audit, requestHash);
        if (!"REJECTED".equals(persisted.getOutcome())) {
            return replayRetry(persisted, requestHash);
        }
        return new RetryDispatchResult(null, PaymentError.valueOf(persisted.getErrorCode()));
    }

    private RefundDispatchRetryAuditEntity retryAudit(
            RetryRefundDispatchCommand command,
            String reason,
            String requestHash,
            RefundOrderEntity refund,
            String outcome,
            String errorCode,
            Instant now) {
        RefundDispatchRetryAuditEntity audit = new RefundDispatchRetryAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCommandId(command.commandId());
        audit.setRequestHash(requestHash);
        audit.setRefundNo(command.refundNo());
        audit.setOperatorId(command.operatorId());
        audit.setReason(reason);
        audit.setOutcome(outcome);
        audit.setErrorCode(errorCode);
        if (refund != null) {
            audit.setBeforeRefundStatus(refund.getStatus());
            audit.setBeforeRequestStatus(refund.getRequestStatus());
            audit.setBeforeRequestAttempts(refund.getRequestAttempts());
            audit.setBeforeLastError(refund.getLastRequestError());
            audit.setAfterRefundStatus(refund.getStatus());
            audit.setAfterRequestStatus(refund.getRequestStatus());
            audit.setAfterRequestAttempts(refund.getRequestAttempts());
        }
        audit.setCreatedAt(now);
        return audit;
    }

    private RefundDispatchRetryAuditEntity persistRetryAudit(
            RefundDispatchRetryAuditEntity audit,
            String requestHash) {
        retryAuditMapper.insertOrLockExisting(audit);
        RefundDispatchRetryAuditEntity persisted =
                retryAuditMapper.selectByCommandIdForUpdate(audit.getCommandId());
        if (persisted == null) {
            throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
        }
        if (!constantEquals(persisted.getRequestHash(), requestHash)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        return persisted;
    }

    private RetryDispatchResult replayRetry(
            RefundDispatchRetryAuditEntity audit,
            String requestHash) {
        if (!constantEquals(audit.getRequestHash(), requestHash)) {
            throw new PaymentException(PaymentError.IDEMPOTENCY_CONFLICT);
        }
        if ("REJECTED".equals(audit.getOutcome())) {
            return new RetryDispatchResult(null, PaymentError.valueOf(audit.getErrorCode()));
        }
        return new RetryDispatchResult(view(requireRefund(audit.getRefundNo())), null);
    }

    private RefundDispatchRetryAuditView retryAuditView(RefundDispatchRetryAuditEntity audit) {
        return new RefundDispatchRetryAuditView(
                audit.getCommandId(), audit.getRefundNo(), audit.getOperatorId(), audit.getReason(),
                audit.getOutcome(), audit.getErrorCode(), audit.getBeforeRefundStatus(),
                audit.getBeforeRequestStatus(), audit.getBeforeRequestAttempts(),
                audit.getBeforeLastError(),
                audit.getAfterRefundStatus(), audit.getAfterRequestStatus(),
                audit.getAfterRequestAttempts(), audit.getCreatedAt());
    }

    private PaymentExceptionRefundAuditEntity paymentExceptionAudit(
            CreatePaymentExceptionRefundCommand command,
            String reason,
            String requestHash,
            Instant now) {
        PaymentExceptionRefundAuditEntity audit =
                new PaymentExceptionRefundAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCommandId(command.commandId());
        audit.setRequestHash(requestHash);
        audit.setPaymentNo(command.paymentNo());
        audit.setOperatorId(command.operatorId());
        audit.setReason(reason);
        audit.setOutcome("PROCESSING");
        audit.setCreatedAt(now);
        return audit;
    }

    private PaymentError paymentExceptionEligibilityError(
            PaymentOrderEntity payment,
            PaymentContext context) {
        if (payment == null) {
            return PaymentError.RESOURCE_NOT_FOUND;
        }
        if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())
                || context == null
                || !"PAYMENT_EXCEPTION".equals(context.status())
                || !Objects.equals(payment.getOrderNo(), context.orderNo())
                || !Objects.equals(payment.getUserId(), context.userId())
                || !Objects.equals(payment.getReservationNo(), context.reservationNo())
                || !Objects.equals(payment.getPaymentNo(), context.paymentNo())
                || payment.getAmount().compareTo(context.totalAmount()) != 0) {
            return PaymentError.PAYMENT_EXCEPTION_REFUND_NOT_ALLOWED;
        }
        return null;
    }

    private void acceptPaymentExceptionAudit(
            PaymentExceptionRefundAuditEntity audit,
            PaymentOrderEntity payment,
            RefundOrderEntity refund) {
        audit.setOrderNo(payment.getOrderNo());
        audit.setRefundNo(refund.getRefundNo());
        audit.setOutcome("ACCEPTED");
        audit.setErrorCode(null);
        requireUpdated(exceptionRefundAuditMapper.updateById(audit));
    }

    private PaymentExceptionRefundResult replayPaymentExceptionRefund(
            PaymentExceptionRefundAuditEntity audit) {
        return switch (audit.getOutcome()) {
            case "ACCEPTED" -> new PaymentExceptionRefundResult(
                    view(requireRefund(audit.getRefundNo())),
                    null);
            case "REJECTED" -> new PaymentExceptionRefundResult(
                    null,
                    PaymentError.valueOf(audit.getErrorCode()));
            default -> throw new PaymentException(PaymentError.CONCURRENT_MODIFICATION);
        };
    }

    private PaymentExceptionRefundAuditView paymentExceptionAuditView(
            PaymentExceptionRefundAuditEntity audit) {
        return new PaymentExceptionRefundAuditView(
                audit.getCommandId(),
                audit.getPaymentNo(),
                audit.getOrderNo(),
                audit.getRefundNo(),
                audit.getOperatorId(),
                audit.getReason(),
                audit.getOutcome(),
                audit.getErrorCode(),
                audit.getCreatedAt());
    }

    private String paymentExceptionReference(String orderNo) {
        return "PEX-" + orderNo;
    }

    private String paymentExceptionCommandHash(
            CreatePaymentExceptionRefundCommand command,
            String reason) {
        return sha256(
                hashPart(command.paymentNo())
                        + hashPart(command.operatorId())
                        + hashPart(reason));
    }

    private String paymentExceptionRefundHash(PaymentOrderEntity payment) {
        return sha256(String.join(
                "|",
                "PAYMENT_EXCEPTION",
                payment.getPaymentNo(),
                payment.getOrderNo(),
                payment.getUserId().toString(),
                payment.getAmount().stripTrailingZeros().toPlainString()));
    }

    private String retryCommandHash(RetryRefundDispatchCommand command, String reason) {
        return sha256(hashPart(command.refundNo()) + hashPart(command.operatorId()) + hashPart(reason));
    }

    private String hashPart(String value) {
        return value.length() + ":" + value;
    }

    private record RetryDispatchResult(RefundView refund, PaymentError error) {
    }

    private record PaymentExceptionRefundResult(
            RefundView refund,
            PaymentError error) {
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
