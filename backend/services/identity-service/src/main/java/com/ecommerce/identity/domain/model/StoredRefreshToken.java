package com.ecommerce.identity.domain.model;

import java.time.Instant;

public record StoredRefreshToken(
        Long id,
        Long userId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt
) {

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
