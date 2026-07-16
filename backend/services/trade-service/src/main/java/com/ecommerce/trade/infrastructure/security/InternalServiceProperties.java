package com.ecommerce.trade.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@Validated
@ConfigurationProperties("ecommerce.security.internal")
public record InternalServiceProperties(
        @NotBlank @Size(min = 32) String token,
        Set<String> allowedCallers
) {
    public InternalServiceProperties {
        allowedCallers = allowedCallers == null ? Set.of() : Set.copyOf(allowedCallers);
    }
}
