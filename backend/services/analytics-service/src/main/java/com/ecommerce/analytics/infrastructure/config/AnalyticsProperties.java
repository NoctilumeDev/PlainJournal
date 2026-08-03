package com.ecommerce.analytics.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

@Validated
@ConfigurationProperties("ecommerce.analytics")
public record AnalyticsProperties(
        @NotBlank String zoneId,
        @Min(1) @Max(3660) int maximumRangeDays,
        @Min(100) @Max(100000) int reconciliationRowLimit) {

    public AnalyticsProperties {
        zoneId = zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId;
        maximumRangeDays = maximumRangeDays <= 0 ? 366 : maximumRangeDays;
        reconciliationRowLimit = reconciliationRowLimit <= 0 ? 10000 : reconciliationRowLimit;
        ZoneId.of(zoneId);
    }

    public ZoneId businessZone() {
        return ZoneId.of(zoneId);
    }
}
