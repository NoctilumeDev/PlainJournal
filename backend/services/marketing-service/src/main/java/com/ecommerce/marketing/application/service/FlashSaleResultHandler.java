package com.ecommerce.marketing.application.service;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleOrderResultCommand;
import com.ecommerce.marketing.domain.FlashSaleAdmissionStatus;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleAdmissionEntity;
import com.ecommerce.marketing.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleAdmissionMapper;
import com.ecommerce.marketing.infrastructure.messaging.FlashSaleResultConsumerProperties;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlashSaleResultHandler {

    public static final String CONSUMER_GROUP = "marketing-flash-sale-result-v1";

    private final ConsumedEventMapper consumedEventMapper;
    private final FlashSaleAdmissionMapper admissionMapper;
    private final String consumerGroup;

    public FlashSaleResultHandler(
            ConsumedEventMapper consumedEventMapper,
            FlashSaleAdmissionMapper admissionMapper,
            FlashSaleResultConsumerProperties properties) {
        this.consumedEventMapper = consumedEventMapper;
        this.admissionMapper = admissionMapper;
        this.consumerGroup = properties.consumerGroup();
    }

    @Transactional
    public void handle(FlashSaleOrderResultCommand command) {
        String payloadFingerprint = PayloadFingerprint.of(command);
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(), consumerGroup, payloadFingerprint,
                admissionMapper.currentTime()) != 1) {
            String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                    command.eventId(), consumerGroup);
            if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        FlashSaleAdmissionEntity admission = admissionMapper.selectByTokenForUpdate(command.requestToken());
        if (admission == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        if (!admission.getActivityNo().equals(command.activityNo())
                || !admission.getUserId().equals(command.userId())) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
        if (FlashSaleAdmissionStatus.ORDER_CREATED.name().equals(admission.getStatus())
                || FlashSaleAdmissionStatus.FAILED.name().equals(admission.getStatus())) {
            requireSameTerminalResult(admission, command);
            return;
        }
        if (!FlashSaleAdmissionStatus.QUEUED.name().equals(admission.getStatus())
                && !FlashSaleAdmissionStatus.RESULT_UNKNOWN.name().equals(admission.getStatus())) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
        switch (command.eventType()) {
            case "FlashSaleOrderSucceeded" -> {
                if (command.orderNo() == null || command.orderNo().isBlank()) {
                    throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
                }
                admission.setStatus(FlashSaleAdmissionStatus.ORDER_CREATED.name());
                admission.setOrderNo(command.orderNo());
                admission.setFailureCode(null);
            }
            case "FlashSaleOrderFailed" -> {
                if (command.failureCode() == null || command.failureCode().isBlank()) {
                    throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
                }
                admission.setStatus(FlashSaleAdmissionStatus.FAILED.name());
                admission.setFailureCode(command.failureCode());
            }
            default -> throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
        admission.setCompletedAt(command.completedAt());
        admission.setUpdatedAt(admissionMapper.currentTime());
        requireUpdated(admissionMapper.updateById(admission));
    }

    private void requireSameTerminalResult(
            FlashSaleAdmissionEntity admission,
            FlashSaleOrderResultCommand command) {
        boolean sameSuccess = FlashSaleAdmissionStatus.ORDER_CREATED.name().equals(admission.getStatus())
                && "FlashSaleOrderSucceeded".equals(command.eventType())
                && admission.getOrderNo().equals(command.orderNo());
        boolean sameFailure = FlashSaleAdmissionStatus.FAILED.name().equals(admission.getStatus())
                && "FlashSaleOrderFailed".equals(command.eventType())
                && admission.getFailureCode().equals(command.failureCode());
        if (!sameSuccess && !sameFailure) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new MarketingException(MarketingError.CONCURRENT_MODIFICATION);
        }
    }
}
