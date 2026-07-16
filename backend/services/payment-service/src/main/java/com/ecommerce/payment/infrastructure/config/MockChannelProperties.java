package com.ecommerce.payment.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ecommerce.payment.mock-channel")
public record MockChannelProperties(
        @NotBlank @Size(min = 32) String callbackSecret,
        Duration callbackMaxSkew
) {
    public MockChannelProperties {
        callbackMaxSkew = callbackMaxSkew == null ? Duration.ofMinutes(5) : callbackMaxSkew;
    }
}
