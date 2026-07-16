package com.ecommerce.trade.application.service;

import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.infrastructure.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.order", name = "recovery-enabled", havingValue = "true")
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    private final TradeOrderService orderService;
    private final OrderProperties properties;

    public OrderRecoveryJob(TradeOrderService orderService, OrderProperties properties) {
        this.orderService = orderService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.order.recovery-delay:5000}")
    public void recoverOrders() {
        for (String orderNo : orderService.findRecoverableOrderNumbers(properties.recoveryBatchSize())) {
            try {
                orderService.recoverOrder(orderNo);
            } catch (TradeException exception) {
                log.debug("Order recovery lost a state race: orderNo={}, code={}", orderNo, exception.error().code());
            } catch (RuntimeException exception) {
                log.warn("Order recovery failed: orderNo={}", orderNo, exception);
            }
        }
        for (String orderNo : orderService.findTimedOutOrderNumbers(properties.recoveryBatchSize())) {
            try {
                orderService.cancelTimedOutOrder(orderNo);
            } catch (TradeException exception) {
                log.debug("Payment timeout lost a state race: orderNo={}, code={}", orderNo, exception.error().code());
            } catch (RuntimeException exception) {
                log.warn("Payment-timeout recovery failed: orderNo={}", orderNo, exception);
            }
        }
    }
}
