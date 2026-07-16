package com.ecommerce.identity.application.port;

import com.ecommerce.identity.domain.model.StoredRefreshToken;
import com.ecommerce.identity.domain.model.UserAccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdentityStore {

    boolean accountExistsByEmail(String normalizedEmail);

    Optional<UserAccount> findAccountByEmail(String normalizedEmail);

    Optional<UserAccount> findAccountById(Long userId);

    UserAccount createAccount(String normalizedEmail, String passwordHash, String displayName, Instant now);

    void assignRole(Long userId, String roleCode, Instant now);

    List<String> findRoleCodes(Long userId);

    void saveRefreshToken(Long userId, String tokenHash, Instant expiresAt, Instant now);

    Optional<StoredRefreshToken> findRefreshTokenByHash(String tokenHash);

    boolean revokeRefreshToken(Long tokenId, Instant now);

    void saveLoginRecord(
            Long userId,
            String normalizedEmail,
            boolean successful,
            String failureCode,
            String clientIp,
            String userAgent,
            Instant now
    );
}
