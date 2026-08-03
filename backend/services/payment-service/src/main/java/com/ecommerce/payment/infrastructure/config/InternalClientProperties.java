package com.ecommerce.payment.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ecommerce.clients.internal")
public record InternalClientProperties(
        @NotBlank @Size(min = 32) String token,
        @NotBlank String caller
) {
}
