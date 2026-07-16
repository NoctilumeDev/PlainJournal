package com.ecommerce.identity.application.service;

import com.ecommerce.identity.application.exception.IdentityError;
import com.ecommerce.identity.application.exception.IdentityException;
import com.ecommerce.identity.application.model.AuthTokens;
import com.ecommerce.identity.application.model.LoginContext;
import com.ecommerce.identity.application.model.UserProfile;
import com.ecommerce.identity.application.port.IdentityStore;
import com.ecommerce.identity.application.port.LoginAttemptStore;
import com.ecommerce.identity.application.port.TokenManager;
import com.ecommerce.identity.domain.model.AccountStatus;
import com.ecommerce.identity.domain.model.RoleCode;
import com.ecommerce.identity.domain.model.StoredRefreshToken;
import com.ecommerce.identity.domain.model.UserAccount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class AuthenticationService {

    private final IdentityStore identityStore;
    private final TokenManager tokenManager;
    private final PasswordEncoder passwordEncoder;
    private final LoginAuditService loginAuditService;
    private final LoginAttemptStore loginAttemptStore;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthenticationService(
            IdentityStore identityStore,
            TokenManager tokenManager,
            PasswordEncoder passwordEncoder,
            LoginAuditService loginAuditService,
            LoginAttemptStore loginAttemptStore,
            Clock clock) {
        this.identityStore = identityStore;
        this.tokenManager = tokenManager;
        this.passwordEncoder = passwordEncoder;
        this.loginAuditService = loginAuditService;
        this.loginAttemptStore = loginAttemptStore;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("identity-timing-protection");
    }

    @Transactional
    public UserProfile register(String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        validatePasswordByteLength(password);
        if (identityStore.accountExistsByEmail(normalizedEmail)) {
            throw new IdentityException(IdentityError.EMAIL_ALREADY_REGISTERED);
        }

        Instant now = clock.instant();
        try {
            UserAccount account = identityStore.createAccount(
                    normalizedEmail,
                    passwordEncoder.encode(password),
                    displayName.strip(),
                    now
            );
            identityStore.assignRole(account.id(), RoleCode.CUSTOMER.name(), now);
            return toProfile(account, List.of(RoleCode.CUSTOMER.name()));
        } catch (DuplicateKeyException exception) {
            throw new IdentityException(IdentityError.EMAIL_ALREADY_REGISTERED);
        }
    }

    @Transactional
    public AuthTokens login(String email, String password, LoginContext context) {
        String normalizedEmail = normalizeEmail(email);
        Instant now = clock.instant();
        if (loginAttemptStore.isBlocked(normalizedEmail, now)) {
            loginAuditService.record(
                    null,
                    normalizedEmail,
                    false,
                    IdentityError.LOGIN_TEMPORARILY_LOCKED.code(),
                    context,
                    now
            );
            throw new IdentityException(IdentityError.LOGIN_TEMPORARILY_LOCKED);
        }
        UserAccount account = identityStore.findAccountByEmail(normalizedEmail).orElse(null);

        String hashToCheck = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hashToCheck);
        if (account == null || !passwordMatches) {
            LoginAttemptStore.FailureResult failure = loginAttemptStore.recordFailure(normalizedEmail, now);
            IdentityError error = failure.blocked()
                    ? IdentityError.LOGIN_TEMPORARILY_LOCKED
                    : IdentityError.INVALID_CREDENTIALS;
            loginAuditService.record(
                    account == null ? null : account.id(),
                    normalizedEmail,
                    false,
                    error.code(),
                    context,
                    now
            );
            throw new IdentityException(error);
        }
        loginAttemptStore.clear(normalizedEmail);
        if (account.status() != AccountStatus.ACTIVE) {
            loginAuditService.record(
                    account.id(),
                    normalizedEmail,
                    false,
                    IdentityError.ACCOUNT_UNAVAILABLE.code(),
                    context,
                    now
            );
            throw new IdentityException(IdentityError.ACCOUNT_UNAVAILABLE);
        }

        AuthTokens tokens = issueTokens(account.id(), identityStore.findRoleCodes(account.id()), now);
        loginAuditService.record(account.id(), normalizedEmail, true, null, context, now);
        return tokens;
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        String tokenHash = tokenManager.hashRefreshToken(rawRefreshToken);
        StoredRefreshToken storedToken = identityStore.findRefreshTokenByHash(tokenHash)
                .filter(token -> token.isUsableAt(now))
                .orElseThrow(() -> new IdentityException(IdentityError.INVALID_REFRESH_TOKEN));

        UserAccount account = identityStore.findAccountById(storedToken.userId())
                .orElseThrow(() -> new IdentityException(IdentityError.INVALID_REFRESH_TOKEN));
        if (account.status() != AccountStatus.ACTIVE) {
            throw new IdentityException(IdentityError.ACCOUNT_UNAVAILABLE);
        }
        if (!identityStore.revokeRefreshToken(storedToken.id(), now)) {
            throw new IdentityException(IdentityError.INVALID_REFRESH_TOKEN);
        }
        return issueTokens(account.id(), identityStore.findRoleCodes(account.id()), now);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = tokenManager.hashRefreshToken(rawRefreshToken);
        identityStore.findRefreshTokenByHash(tokenHash)
                .ifPresent(token -> identityStore.revokeRefreshToken(token.id(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public UserProfile currentUser(Long userId) {
        UserAccount account = identityStore.findAccountById(userId)
                .orElseThrow(() -> new IdentityException(IdentityError.ACCOUNT_NOT_FOUND));
        return toProfile(account, identityStore.findRoleCodes(account.id()));
    }

    private AuthTokens issueTokens(Long userId, List<String> roleCodes, Instant now) {
        TokenManager.AccessToken accessToken = tokenManager.createAccessToken(userId, roleCodes, now);
        TokenManager.RefreshToken refreshToken = tokenManager.createRefreshToken(now);
        identityStore.saveRefreshToken(userId, refreshToken.hash(), refreshToken.expiresAt(), now);
        return new AuthTokens(
                "Bearer",
                accessToken.value(),
                accessToken.expiresInSeconds(),
                refreshToken.value()
        );
    }

    private UserProfile toProfile(UserAccount account, List<String> roles) {
        return new UserProfile(
                account.id(),
                account.email(),
                account.displayName(),
                account.status().name(),
                roles
        );
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordByteLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentityException(IdentityError.INVALID_PASSWORD);
        }
    }
}
