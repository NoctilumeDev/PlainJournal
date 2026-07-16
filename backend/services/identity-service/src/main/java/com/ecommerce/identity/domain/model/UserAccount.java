package com.ecommerce.identity.domain.model;

import java.time.Instant;

public record UserAccount(
        Long id,
        String email,
        String passwordHash,
        String displayName,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
