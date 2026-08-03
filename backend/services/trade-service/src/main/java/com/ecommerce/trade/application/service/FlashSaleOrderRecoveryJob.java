package com.ecommerce.trade.application.service;

import com.ecommerce.trade.infrastructure.config.TradeSchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.trade.flash-sale-consumer",
        name = "enabled",
        havingValue = "true")
public class FlashSaleOrderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleOrderRecoveryJob.class);

    private final FlashSaleOrderService orderService;

    public FlashSaleOrderRecoveryJob(FlashSaleOrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.trade.flash-sale-consumer.recovery-delay:1000}",
            scheduler = TradeSchedulingConfig.FLASH_SALE_SCHEDULER)
    public void recover() {
        for (String requestToken : orderService.findRecoverableTokens()) {
            try {
                orderService.recover(requestToken);
            } catch (RuntimeException exception) {
                log.warn("Flash-sale order recovery failed: requestToken={}", requestToken, exception);
            }
        }
    }
}
