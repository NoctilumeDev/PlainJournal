package com.ecommerce.gateway.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ecommerce.security.token")
public record GatewayTokenProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotBlank String issuer) {
}
