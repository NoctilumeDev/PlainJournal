package com.ecommerce.identity.application.model;

public record AuthTokens(
        String tokenType,
        String accessToken,
        long expiresIn,
        String refreshToken
) {
}
