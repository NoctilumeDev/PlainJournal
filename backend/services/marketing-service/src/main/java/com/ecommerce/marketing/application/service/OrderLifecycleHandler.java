package com.ecommerce.marketing.application.service;

import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.OrderLifecycleCommand;
import com.ecommerce.marketing.infrastructure.persistence.mapper.ConsumedEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OrderLifecycleHandler {

    public static final String CONSUMER_GROUP = "marketing-order-lifecycle-v1";

    private final ConsumedEventMapper consumedEventMapper;
    private final MarketingService marketingService;
    private final Clock clock;

    public OrderLifecycleHandler(
            ConsumedEventMapper consumedEventMapper,
            MarketingService marketingService,
            Clock clock) {
        this.consumedEventMapper = consumedEventMapper;
        this.marketingService = marketingService;
        this.clock = clock;
    }

    @Transactional
    public void handle(OrderLifecycleCommand command) {
        if (consumedEventMapper.insertIfAbsent(command.eventId(), CONSUMER_GROUP, clock.instant()) != 1) {
            return;
        }
        try {
            switch (command.eventType()) {
                case "OrderPaid" -> marketingService.redeem(command.orderNo());
                case "OrderCanceled", "OrderClosed" -> marketingService.release(command.orderNo());
                default -> throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
            }
        } catch (MarketingException exception) {
            if (exception.error() != MarketingError.RESOURCE_NOT_FOUND) {
                throw exception;
            }
            // Orders created before marketing locks existed have nothing to release or redeem.
        }
    }
}
