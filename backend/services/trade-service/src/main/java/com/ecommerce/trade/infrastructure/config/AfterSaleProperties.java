package com.ecommerce.trade.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ecommerce.trade.after-sale")
public record AfterSaleProperties(Duration applicationWindow) {

    public AfterSaleProperties {
        applicationWindow = applicationWindow == null ? Duration.ofDays(30) : applicationWindow;
        if (applicationWindow.isNegative() || applicationWindow.isZero()) {
            throw new IllegalArgumentException("After-sale application window must be positive");
        }
    }
}
