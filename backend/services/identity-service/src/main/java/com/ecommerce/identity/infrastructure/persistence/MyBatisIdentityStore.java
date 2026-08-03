package com.ecommerce.identity.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.identity.application.port.IdentityStore;
import com.ecommerce.identity.domain.model.AccountStatus;
import com.ecommerce.identity.domain.model.StoredRefreshToken;
import com.ecommerce.identity.domain.model.UserAccount;
import com.ecommerce.identity.infrastructure.persistence.entity.LoginRecordEntity;
import com.ecommerce.identity.infrastructure.persistence.entity.RefreshTokenEntity;
import com.ecommerce.identity.infrastructure.persistence.entity.RoleEntity;
import com.ecommerce.identity.infrastructure.persistence.entity.UserAccountEntity;
import com.ecommerce.identity.infrastructure.persistence.entity.UserRoleEntity;
import com.ecommerce.identity.infrastructure.persistence.mapper.LoginRecordMapper;
import com.ecommerce.identity.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.ecommerce.identity.infrastructure.persistence.mapper.RoleMapper;
import com.ecommerce.identity.infrastructure.persistence.mapper.UserAccountMapper;
import com.ecommerce.identity.infrastructure.persistence.mapper.UserRoleMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisIdentityStore implements IdentityStore {

    private final UserAccountMapper accountMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final LoginRecordMapper loginRecordMapper;

    public MyBatisIdentityStore(
            UserAccountMapper accountMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            RefreshTokenMapper refreshTokenMapper,
            LoginRecordMapper loginRecordMapper) {
        this.accountMapper = accountMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.loginRecordMapper = loginRecordMapper;
    }

    @Override
    public Instant currentTime() {
        return accountMapper.currentTime();
    }

    @Override
    public boolean accountExistsByEmail(String normalizedEmail) {
        return accountMapper.exists(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getEmail, normalizedEmail));
    }

    @Override
    public Optional<UserAccount> findAccountByEmail(String normalizedEmail) {
        return Optional.ofNullable(accountMapper.selectOne(
                        new LambdaQueryWrapper<UserAccountEntity>()
                                .eq(UserAccountEntity::getEmail, normalizedEmail)))
                .map(this::toDomain);
    }

    @Override
    public Optional<UserAccount> findAccountById(Long userId) {
        return Optional.ofNullable(accountMapper.selectById(userId)).map(this::toDomain);
    }

    @Override
    public UserAccount createAccount(
            String normalizedEmail,
            String passwordHash,
            String displayName,
            Instant now) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setEmail(normalizedEmail);
        entity.setPasswordHash(passwordHash);
        entity.setDisplayName(displayName);
        entity.setStatus(AccountStatus.ACTIVE.name());
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        accountMapper.insert(entity);
        return toDomain(entity);
    }

    @Override
    public void assignRole(Long userId, String roleCode, Instant now) {
        RoleEntity role = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, roleCode));
        if (role == null) {
            throw new IllegalStateException("Role is not initialized: " + roleCode);
        }

        UserRoleEntity assignment = new UserRoleEntity();
        assignment.setUserId(userId);
        assignment.setRoleId(role.getId());
        assignment.setCreatedAt(now);
        userRoleMapper.insertAssignment(assignment);
    }

    @Override
    public List<String> findRoleCodes(Long userId) {
        return roleMapper.selectCodesByUserId(userId);
    }

    @Override
    public void saveRefreshToken(Long userId, String tokenHash, Instant expiresAt, Instant now) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(now);
        refreshTokenMapper.insert(entity);
    }

    @Override
    public Optional<StoredRefreshToken> findRefreshTokenByHash(String tokenHash) {
        return Optional.ofNullable(refreshTokenMapper.selectOne(
                        new LambdaQueryWrapper<RefreshTokenEntity>()
                                .eq(RefreshTokenEntity::getTokenHash, tokenHash)))
                .map(this::toDomain);
    }

    @Override
    public boolean revokeRefreshToken(Long tokenId, Instant now) {
        int updated = refreshTokenMapper.update(null,
                new LambdaUpdateWrapper<RefreshTokenEntity>()
                        .eq(RefreshTokenEntity::getId, tokenId)
                        .isNull(RefreshTokenEntity::getRevokedAt)
                        .set(RefreshTokenEntity::getRevokedAt, now)
                        .set(RefreshTokenEntity::getLastUsedAt, now));
        return updated == 1;
    }

    @Override
    public void saveLoginRecord(
            Long userId,
            String normalizedEmail,
            boolean successful,
            String failureCode,
            String clientIp,
            String userAgent,
            Instant now) {
        LoginRecordEntity entity = new LoginRecordEntity();
        entity.setUserId(userId);
        entity.setNormalizedEmail(normalizedEmail);
        entity.setSuccessful(successful);
        entity.setFailureCode(failureCode);
        entity.setClientIp(clientIp);
        entity.setUserAgent(userAgent);
        entity.setCreatedAt(now);
        loginRecordMapper.insert(entity);
    }

    private UserAccount toDomain(UserAccountEntity entity) {
        return new UserAccount(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getDisplayName(),
                AccountStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private StoredRefreshToken toDomain(RefreshTokenEntity entity) {
        return new StoredRefreshToken(
                entity.getId(),
                entity.getUserId(),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getRevokedAt()
        );
    }
}
