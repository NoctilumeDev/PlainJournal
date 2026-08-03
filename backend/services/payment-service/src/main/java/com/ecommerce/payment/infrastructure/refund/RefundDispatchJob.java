package com.ecommerce.payment.infrastructure.refund;

import com.ecommerce.payment.application.port.RefundChannelPort;
import com.ecommerce.payment.infrastructure.config.PaymentSchedulingConfig;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundOrderEntity;
import com.ecommerce.payment.infrastructure.persistence.mapper.RefundOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "ecommerce.payment.refund-dispatch", name = "enabled", havingValue = "true")
public class RefundDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(RefundDispatchJob.class);

    private final RefundOrderMapper refundMapper;
    private final RefundChannelPort refundChannel;
    private final RefundDispatchProperties properties;

    public RefundDispatchJob(
            RefundOrderMapper refundMapper,
            RefundChannelPort refundChannel,
            RefundDispatchProperties properties) {
        this.refundMapper = refundMapper;
        this.refundChannel = refundChannel;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.payment.refund-dispatch.fixed-delay:2000}",
            initialDelayString = "${ecommerce.payment.refund-dispatch.initial-delay:0}",
            scheduler = PaymentSchedulingConfig.CONTROL_SCHEDULER)
    public void dispatchDueRefunds() {
        Instant now = refundMapper.currentTime();
        refundMapper.resetStaleRequestClaims(now, now);
        // Database timestamps are stored with millisecond precision. A freshly persisted due time
        // can therefore round just beyond the nanosecond-precision application clock.
        Instant dueCutoff = now.plusMillis(1);
        for (RefundOrderEntity refund : refundMapper.selectDueRequests(dueCutoff, properties.batchSize())) {
            if (refundMapper.claimRequest(
                    refund.getId(),
                    properties.dispatcherId(),
                    refund.getRequestAttempts(),
                    dueCutoff,
                    now,
                    now.plus(properties.claimTimeout())) != 1) {
                continue;
            }
            try {
                refundChannel.requestRefund(new RefundChannelPort.RefundRequest(
                        refund.getRefundNo(), refund.getPaymentNo(), refund.getChannel(), refund.getAmount()));
                Instant completedAt = refundMapper.currentTime();
                if (refundMapper.markRequestSent(
                        refund.getId(), properties.dispatcherId(), completedAt) != 1) {
                    log.warn("Refund request was accepted by the channel after its dispatch lease was lost: "
                            + "refundNo={}", refund.getRefundNo());
                }
            } catch (Exception exception) {
                Instant failedAt = refundMapper.currentTime();
                int updated = refundMapper.markRequestFailed(
                        refund.getId(),
                        properties.dispatcherId(),
                        properties.maxAttempts(),
                        failedAt.plus(properties.retryDelay()),
                        conciseError(exception),
                        failedAt);
                if (updated == 1) {
                    log.warn("Refund request dispatch failed and remains governed by persisted retry state: "
                                    + "refundNo={}",
                            refund.getRefundNo());
                } else {
                    log.warn("Refund request failed after its dispatch lease was lost: refundNo={}",
                            refund.getRefundNo());
                }
            }
        }
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
