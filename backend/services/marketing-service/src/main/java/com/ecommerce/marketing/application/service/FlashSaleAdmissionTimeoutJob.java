package com.ecommerce.marketing.application.service;

import com.ecommerce.marketing.infrastructure.config.MarketingSchedulingConfig;
import com.ecommerce.marketing.infrastructure.flashsale.FlashSaleAdmissionProperties;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleAdmissionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FlashSaleAdmissionTimeoutJob {

    private final FlashSaleAdmissionMapper admissionMapper;
    private final FlashSaleAdmissionProperties properties;

    public FlashSaleAdmissionTimeoutJob(
            FlashSaleAdmissionMapper admissionMapper,
            FlashSaleAdmissionProperties properties) {
        this.admissionMapper = admissionMapper;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.marketing.flash-sale.timeout-scan-delay:5000}",
            scheduler = MarketingSchedulingConfig.CONTROL_SCHEDULER)
    public void markUnknownResults() {
        Instant now = admissionMapper.currentTime();
        Instant acceptedBefore = now.minus(properties.processingTimeout());
        for (String token : admissionMapper.selectTimedOutTokens(
                acceptedBefore, properties.timeoutScanBatchSize())) {
            admissionMapper.markResultUnknown(token, now);
        }
    }
}
