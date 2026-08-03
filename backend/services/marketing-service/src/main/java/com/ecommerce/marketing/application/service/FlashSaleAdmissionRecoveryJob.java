package com.ecommerce.marketing.application.service;

import com.ecommerce.marketing.infrastructure.config.MarketingSchedulingConfig;
import com.ecommerce.marketing.infrastructure.flashsale.FlashSaleAdmissionProperties;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleAdmissionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FlashSaleAdmissionRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleAdmissionRecoveryJob.class);

    private final FlashSaleAdmissionMapper admissionMapper;
    private final FlashSaleService flashSaleService;
    private final FlashSaleAdmissionProperties properties;

    public FlashSaleAdmissionRecoveryJob(
            FlashSaleAdmissionMapper admissionMapper,
            FlashSaleService flashSaleService,
            FlashSaleAdmissionProperties properties) {
        this.admissionMapper = admissionMapper;
        this.flashSaleService = flashSaleService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.marketing.flash-sale.pending-recovery-delay:1000}",
            scheduler = MarketingSchedulingConfig.CONTROL_SCHEDULER)
    public void recoverPendingAdmissions() {
        for (String token : admissionMapper.selectPendingTokens(
                properties.pendingRecoveryBatchSize())) {
            try {
                flashSaleService.recoverPendingAdmission(token);
            } catch (RuntimeException exception) {
                log.warn("Flash-sale pending admission recovery failed: requestToken={}",
                        token, exception);
            }
        }
    }
}
