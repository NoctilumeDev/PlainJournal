package com.ecommerce.trade.application.service;

import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.infrastructure.config.OrderProperties;
import com.ecommerce.trade.infrastructure.config.TradeSchedulingConfig;
import com.ecommerce.trade.infrastructure.observability.TradeOrderRecoveryObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.order", name = "recovery-enabled", havingValue = "true")
public class OrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryJob.class);

    private final TradeOrderService orderService;
    private final OrderProperties properties;
    private final TradeOrderRecoveryObservability observability;
    private final String owner = "trade-order-recovery-"
            + UUID.randomUUID().toString().replace("-", "");

    public OrderRecoveryJob(
            TradeOrderService orderService,
            OrderProperties properties,
            TradeOrderRecoveryObservability observability) {
        this.orderService = orderService;
        this.properties = properties;
        this.observability = observability;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.trade.order.recovery-delay:5000}",
            scheduler = TradeSchedulingConfig.ORDER_RECOVERY_SCHEDULER)
    public void recoverOrders() {
        observability.observe(this::recoverOrdersOnce);
    }

    private void recoverOrdersOnce() {
        for (String orderNo : orderService.findRecoverableOrderNumbers(properties.recoveryBatchSize())) {
            if (!orderService.tryClaimRecovery(orderNo, owner)) {
                continue;
            }
            try {
                orderService.recoverOrder(orderNo);
            } catch (TradeException exception) {
                log.debug("Order recovery lost a state race: orderNo={}, code={}", orderNo, exception.error().code());
            } catch (RuntimeException exception) {
                log.warn("Order recovery failed: orderNo={}", orderNo, exception);
            } finally {
                orderService.releaseRecoveryClaim(orderNo, owner);
            }
        }
        for (String orderNo : orderService.findTimedOutOrderNumbers(properties.recoveryBatchSize())) {
            if (!orderService.tryClaimRecovery(orderNo, owner)) {
                continue;
            }
            try {
                orderService.cancelTimedOutOrder(orderNo);
            } catch (TradeException exception) {
                log.debug("Payment timeout lost a state race: orderNo={}, code={}", orderNo, exception.error().code());
            } catch (RuntimeException exception) {
                log.warn("Payment-timeout recovery failed: orderNo={}", orderNo, exception);
            } finally {
                orderService.releaseRecoveryClaim(orderNo, owner);
            }
        }
    }
}
