package com.ecommerce.marketing.application.service;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.OrderLifecycleCommand;
import com.ecommerce.marketing.infrastructure.persistence.mapper.ConsumedEventMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.PricingLockMapper;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleHandler {

    public static final String CONSUMER_GROUP = "marketing-order-lifecycle-v1";

    private final ConsumedEventMapper consumedEventMapper;
    private final PricingLockMapper pricingLockMapper;
    private final MarketingService marketingService;

    public OrderLifecycleHandler(
            ConsumedEventMapper consumedEventMapper,
            PricingLockMapper pricingLockMapper,
            MarketingService marketingService) {
        this.consumedEventMapper = consumedEventMapper;
        this.pricingLockMapper = pricingLockMapper;
        this.marketingService = marketingService;
    }

    @Transactional
    public void handle(OrderLifecycleCommand command) {
        String payloadFingerprint = PayloadFingerprint.of(command);
        if (consumedEventMapper.insertIfAbsent(
                command.eventId(), CONSUMER_GROUP, payloadFingerprint,
                pricingLockMapper.currentTime()) != 1) {
            String storedFingerprint = consumedEventMapper.selectPayloadFingerprint(
                    command.eventId(), CONSUMER_GROUP);
            if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        switch (command.eventType()) {
            case "OrderPaid" -> marketingService.redeemIfPresent(command.orderNo());
            case "OrderCanceled", "OrderClosed" -> marketingService.releaseIfPresent(command.orderNo());
            default -> throw new IllegalArgumentException("Unsupported order lifecycle event: " + command.eventType());
        }
    }
}
