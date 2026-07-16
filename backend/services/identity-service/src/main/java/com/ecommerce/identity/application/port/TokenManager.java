package com.ecommerce.identity.application.port;

import java.time.Instant;
import java.util.List;

public interface TokenManager {

    AccessToken createAccessToken(Long userId, List<String> roleCodes, Instant now);

    RefreshToken createRefreshToken(Instant now);

    String hashRefreshToken(String rawToken);

    record AccessToken(String value, long expiresInSeconds) {
    }

    record RefreshToken(String value, String hash, Instant expiresAt) {
    }
}
