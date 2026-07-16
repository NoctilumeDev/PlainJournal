package com.ecommerce.identity.application.service;

import com.ecommerce.identity.application.model.LoginContext;
import com.ecommerce.identity.application.port.IdentityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LoginAuditService {

    private final IdentityStore identityStore;

    public LoginAuditService(IdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long userId,
            String normalizedEmail,
            boolean successful,
            String failureCode,
            LoginContext context,
            Instant now) {
        identityStore.saveLoginRecord(
                userId,
                normalizedEmail,
                successful,
                failureCode,
                context.clientIp(),
                context.userAgent(),
                now
        );
    }
}
