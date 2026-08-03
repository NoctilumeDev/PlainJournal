package com.ecommerce.marketing.infrastructure.flashsale;

import com.ecommerce.marketing.application.port.FlashSaleAdmissionStore;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStoreException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.marketing.flash-sale",
        name = "redis-enabled",
        havingValue = "false")
public class UnavailableFlashSaleAdmissionStore implements FlashSaleAdmissionStore {

    private static final String MESSAGE = "Flash-sale admission requires Redis";

    @Override
    public void preheat(Activity activity, Instant now) {
        throw new FlashSaleAdmissionStoreException(MESSAGE);
    }

    @Override
    public Decision admit(
            String activityNo,
            Long userId,
            String requestKey,
            String candidateToken,
            Instant now) {
        throw new FlashSaleAdmissionStoreException(MESSAGE);
    }

    @Override
    public Optional<Snapshot> find(String requestToken) {
        throw new FlashSaleAdmissionStoreException(MESSAGE);
    }
}
