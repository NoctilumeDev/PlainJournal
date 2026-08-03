package com.ecommerce.marketing.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.FlashSaleModels.CreateFlashSaleCommand;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleActivityView;
import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleAdmissionView;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStore;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStore.Decision;
import com.ecommerce.marketing.application.port.FlashSaleAdmissionStoreException;
import com.ecommerce.marketing.domain.FlashSaleAdmissionStatus;
import com.ecommerce.marketing.domain.FlashSaleActivityStatus;
import com.ecommerce.marketing.domain.OutboxStatus;
import com.ecommerce.marketing.infrastructure.flashsale.FlashSaleAdmissionProperties;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleActivityEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleAdmissionEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleOutboxEventEntity;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleAdmissionMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleActivityMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.FlashSaleOutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FlashSaleService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleService.class);

    private final FlashSaleActivityMapper activityMapper;
    private final FlashSaleAdmissionMapper admissionMapper;
    private final FlashSaleOutboxEventMapper outboxMapper;
    private final FlashSaleAdmissionStore admissionStore;
    private final FlashSaleAdmissionProperties admissionProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public FlashSaleService(
            FlashSaleActivityMapper activityMapper,
            FlashSaleAdmissionMapper admissionMapper,
            FlashSaleOutboxEventMapper outboxMapper,
            FlashSaleAdmissionStore admissionStore,
            FlashSaleAdmissionProperties admissionProperties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.activityMapper = activityMapper;
        this.admissionMapper = admissionMapper;
        this.outboxMapper = outboxMapper;
        this.admissionStore = admissionStore;
        this.admissionProperties = admissionProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public FlashSaleActivityView create(CreateFlashSaleCommand command) {
        ValidatedActivity validated = validate(command);
        Instant now = activityMapper.currentTime();
        long id = IdWorker.getId();
        FlashSaleActivityEntity activity = new FlashSaleActivityEntity();
        activity.setId(id);
        activity.setActivityNo("FSA" + id);
        activity.setName(validated.name());
        activity.setProductId(validated.productId());
        activity.setSkuId(validated.skuId());
        activity.setSalePrice(validated.salePrice());
        activity.setAdmissionLimit(validated.admissionLimit());
        activity.setStatus(FlashSaleActivityStatus.DRAFT.name());
        activity.setStartsAt(validated.startsAt());
        activity.setEndsAt(validated.endsAt());
        activity.setVersion(0);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        activityMapper.insert(activity);
        return view(activity);
    }

    public FlashSaleActivityView publish(String activityNo) {
        FlashSaleActivityEntity candidate = requireActivity(activityNo);
        FlashSaleActivityStatus candidateStatus = status(candidate);
        if (candidateStatus == FlashSaleActivityStatus.ACTIVE) {
            return view(candidate);
        }
        if (candidateStatus != FlashSaleActivityStatus.DRAFT) {
            throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE);
        }
        Instant now = activityMapper.currentTime();
        if (!now.isBefore(candidate.getEndsAt())) {
            throw new MarketingException(MarketingError.FLASH_SALE_ENDED);
        }
        try {
            preheat(candidate, 0, now);
        } catch (FlashSaleAdmissionStoreException exception) {
            FlashSaleActivityEntity latest = requireActivity(activityNo);
            if (status(latest) == FlashSaleActivityStatus.ACTIVE) {
                return view(latest);
            }
            throw unavailable(exception);
        }
        return transactionTemplate.execute(transactionStatus -> {
            FlashSaleActivityEntity activity = requireActivityForUpdate(activityNo);
            FlashSaleActivityStatus currentStatus = status(activity);
            if (currentStatus == FlashSaleActivityStatus.ACTIVE) {
                return view(activity);
            }
            if (currentStatus != FlashSaleActivityStatus.DRAFT) {
                throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE);
            }
            Instant activatedAt = activityMapper.currentTime();
            if (!activatedAt.isBefore(activity.getEndsAt())) {
                throw new MarketingException(MarketingError.FLASH_SALE_ENDED);
            }
            activity.setStatus(FlashSaleActivityStatus.ACTIVE.name());
            activity.setUpdatedAt(activatedAt);
            requireUpdated(activityMapper.updateById(activity));
            return view(activity);
        });
    }

    @Transactional(readOnly = true)
    public FlashSaleActivityView getActivity(String activityNo) {
        return view(requireActivity(activityNo));
    }

    public FlashSaleAdmissionView admit(
            Long userId,
            String activityNo,
            String requestKey,
            Long addressId) {
        if (userId == null || userId <= 0
                || addressId == null || addressId <= 0
                || requestKey == null || requestKey.isBlank()) {
            throw new MarketingException(MarketingError.INVALID_FLASH_SALE);
        }
        FlashSaleActivityEntity activity = requireActivity(activityNo);
        Instant now = activityMapper.currentTime();
        validateAdmissionWindow(activity, now);
        FlashSaleAdmissionEntity admission = preparePendingAdmission(
                activity, userId, addressId, now);
        if (!FlashSaleAdmissionStatus.ADMISSION_PENDING.name().equals(admission.getStatus())) {
            return existingAdmissionResult(admission);
        }
        try {
            return admissionView(continuePendingAdmission(activity, admission, now));
        } catch (FlashSaleAdmissionStoreException exception) {
            throw unavailable(exception);
        }
    }

    public void recoverPendingAdmission(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) {
            return;
        }
        FlashSaleAdmissionEntity admission = admissionMapper.selectByToken(requestToken.trim());
        if (admission == null
                || !FlashSaleAdmissionStatus.ADMISSION_PENDING.name().equals(admission.getStatus())) {
            return;
        }
        FlashSaleActivityEntity activity = activityMapper.selectByActivityNo(admission.getActivityNo());
        if (activity == null) {
            rejectPending(admission.getRequestToken(), MarketingError.RESOURCE_NOT_FOUND, null);
            return;
        }
        Instant now = activityMapper.currentTime();
        try {
            validateAdmissionWindow(activity, now);
            continuePendingAdmission(activity, admission, now);
        } catch (FlashSaleAdmissionStoreException exception) {
            log.debug(
                    "Flash-sale admission remains pending after a recoverable store failure: "
                            + "requestToken={}, error={}",
                    admission.getRequestToken(),
                    exception.getClass().getSimpleName());
        } catch (MarketingException exception) {
            if (exception.error() == MarketingError.FLASH_SALE_ENDED) {
                rejectPending(
                        admission.getRequestToken(),
                        MarketingError.FLASH_SALE_ENDED,
                        null);
            }
            if (exception.error() == MarketingError.FLASH_SALE_ADMISSION_UNAVAILABLE
                    || exception.error() == MarketingError.FLASH_SALE_NOT_READY
                    || exception.error() == MarketingError.FLASH_SALE_NOT_STARTED
                    || exception.error() == MarketingError.FLASH_SALE_SOLD_OUT
                    || exception.error() == MarketingError.FLASH_SALE_ENDED) {
                return;
            }
            throw exception;
        }
    }

    public FlashSaleAdmissionView getAdmission(Long userId, String requestToken) {
        if (userId == null || userId <= 0 || requestToken == null || requestToken.isBlank()) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        FlashSaleAdmissionEntity admission = admissionMapper.selectByToken(requestToken.trim());
        if (admission == null || !userId.equals(admission.getUserId())) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return admissionView(admission);
    }

    private ValidatedActivity validate(CreateFlashSaleCommand command) {
        if (command == null
                || command.name() == null
                || command.name().isBlank()
                || command.name().trim().length() > 120
                || command.productId() == null
                || command.productId() <= 0
                || command.skuId() == null
                || command.skuId() <= 0
                || command.salePrice() == null
                || command.admissionLimit() <= 0
                || command.startsAt() == null
                || command.endsAt() == null
                || !command.endsAt().isAfter(command.startsAt())
                || !command.endsAt().isAfter(activityMapper.currentTime())) {
            throw new MarketingException(MarketingError.INVALID_FLASH_SALE);
        }
        BigDecimal salePrice;
        try {
            salePrice = command.salePrice().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new MarketingException(MarketingError.INVALID_FLASH_SALE, exception);
        }
        if (salePrice.signum() <= 0) {
            throw new MarketingException(MarketingError.INVALID_FLASH_SALE);
        }
        return new ValidatedActivity(
                command.name().trim(),
                command.productId(),
                command.skuId(),
                salePrice,
                command.admissionLimit(),
                command.startsAt(),
                command.endsAt());
    }

    private void validateAdmissionWindow(FlashSaleActivityEntity activity, Instant now) {
        FlashSaleActivityStatus activityStatus = status(activity);
        if (activityStatus == FlashSaleActivityStatus.DRAFT) {
            throw new MarketingException(MarketingError.FLASH_SALE_NOT_READY);
        }
        if (activityStatus != FlashSaleActivityStatus.ACTIVE) {
            throw new MarketingException(MarketingError.FLASH_SALE_ENDED);
        }
        if (now.isBefore(activity.getStartsAt())) {
            throw new MarketingException(MarketingError.FLASH_SALE_NOT_STARTED);
        }
        if (!now.isBefore(activity.getEndsAt())) {
            throw new MarketingException(MarketingError.FLASH_SALE_ENDED);
        }
    }

    private FlashSaleActivityEntity requireActivity(String activityNo) {
        if (activityNo == null || activityNo.isBlank()) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        FlashSaleActivityEntity activity = activityMapper.selectByActivityNo(activityNo.trim());
        if (activity == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return activity;
    }

    private FlashSaleActivityEntity requireActivityForUpdate(String activityNo) {
        if (activityNo == null || activityNo.isBlank()) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        FlashSaleActivityEntity activity = activityMapper.selectByActivityNoForUpdate(activityNo.trim());
        if (activity == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return activity;
    }

    private FlashSaleActivityStatus status(FlashSaleActivityEntity activity) {
        if (activity.getStatus() == null) {
            throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE);
        }
        try {
            return FlashSaleActivityStatus.valueOf(activity.getStatus());
        } catch (IllegalArgumentException exception) {
            throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE, exception);
        }
    }

    private FlashSaleAdmissionView admissionView(FlashSaleAdmissionEntity admission) {
        return new FlashSaleAdmissionView(
                admission.getRequestToken(),
                admission.getActivityNo(),
                admission.getUserId(),
                admission.getStatus(),
                admission.getRemainingAdmissions(),
                admission.getAcceptedAt(),
                admission.getOrderNo(),
                admission.getFailureCode(),
                admission.getCompletedAt());
    }

    private FlashSaleActivityView view(FlashSaleActivityEntity activity) {
        return new FlashSaleActivityView(
                activity.getActivityNo(),
                activity.getName(),
                activity.getProductId(),
                activity.getSkuId(),
                activity.getSalePrice(),
                activity.getAdmissionLimit(),
                activity.getStatus(),
                activity.getStartsAt(),
                activity.getEndsAt(),
                activity.getVersion());
    }

    private MarketingException unavailable(FlashSaleAdmissionStoreException cause) {
        return new MarketingException(MarketingError.FLASH_SALE_ADMISSION_UNAVAILABLE, cause);
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new MarketingException(MarketingError.CONCURRENT_MODIFICATION);
        }
    }

    private record ValidatedActivity(
            String name,
            Long productId,
            Long skuId,
            BigDecimal salePrice,
            int admissionLimit,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    private FlashSaleAdmissionEntity preparePendingAdmission(
            FlashSaleActivityEntity activity,
            Long userId,
            Long addressId,
            Instant now) {
        return transactionTemplate.execute(status -> {
            FlashSaleAdmissionEntity candidate = new FlashSaleAdmissionEntity();
            candidate.setId(IdWorker.getId());
            candidate.setRequestToken("FST" + UUID.randomUUID().toString().replace("-", ""));
            candidate.setActivityNo(activity.getActivityNo());
            candidate.setUserId(userId);
            candidate.setAddressId(addressId);
            candidate.setRequestHash(admissionHash(activity.getActivityNo(), userId, addressId));
            candidate.setStatus(FlashSaleAdmissionStatus.ADMISSION_PENDING.name());
            candidate.setRemainingAdmissions(null);
            candidate.setVersion(0);
            candidate.setAcceptedAt(null);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            admissionMapper.insertOrLockExisting(candidate);

            FlashSaleAdmissionEntity stored = admissionMapper.selectByActivityAndUserForUpdate(
                    activity.getActivityNo(), userId);
            if (stored == null
                    || !stored.getRequestHash().equals(candidate.getRequestHash())) {
                throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
            }
            return stored;
        });
    }

    private FlashSaleAdmissionEntity continuePendingAdmission(
            FlashSaleActivityEntity activity,
            FlashSaleAdmissionEntity admission,
            Instant now) {
        Decision decision = admissionStore.admit(
                activity.getActivityNo(),
                admission.getUserId(),
                admission.getRequestToken(),
                admission.getRequestToken(),
                now);
        if (decision.outcome() == FlashSaleAdmissionStore.Outcome.NOT_READY) {
            int acceptedCount = admissionMapper.countAcceptedByActivity(activity.getActivityNo());
            preheat(activity, acceptedCount, now);
            decision = admissionStore.admit(
                    activity.getActivityNo(),
                    admission.getUserId(),
                    admission.getRequestToken(),
                    admission.getRequestToken(),
                    now);
        }
        return switch (decision.outcome()) {
            case ACCEPTED, REPLAYED -> finalizeAcceptedAdmission(activity, admission, decision);
            case NOT_READY, NOT_ACTIVE ->
                    throw new MarketingException(MarketingError.FLASH_SALE_NOT_READY);
            case NOT_STARTED -> throw new MarketingException(MarketingError.FLASH_SALE_NOT_STARTED);
            case ENDED -> {
                rejectPending(admission.getRequestToken(), MarketingError.FLASH_SALE_ENDED,
                        decision.remainingAdmissions());
                throw new MarketingException(MarketingError.FLASH_SALE_ENDED);
            }
            case SOLD_OUT -> {
                rejectPending(admission.getRequestToken(), MarketingError.FLASH_SALE_SOLD_OUT,
                        decision.remainingAdmissions());
                throw new MarketingException(MarketingError.FLASH_SALE_SOLD_OUT);
            }
        };
    }

    private FlashSaleAdmissionEntity finalizeAcceptedAdmission(
            FlashSaleActivityEntity activity,
            FlashSaleAdmissionEntity admission,
            Decision decision) {
        if (decision.requestToken() == null
                || !decision.requestToken().equals(admission.getRequestToken())
                || decision.acceptedAt() == null
                || decision.remainingAdmissions() < 0
                || activity.getProductId() == null) {
            throw new MarketingException(MarketingError.FLASH_SALE_ADMISSION_UNAVAILABLE);
        }
        return transactionTemplate.execute(status -> {
            FlashSaleAdmissionEntity stored =
                    admissionMapper.selectByTokenForUpdate(admission.getRequestToken());
            requireSameAdmission(stored, admission);
            if (!FlashSaleAdmissionStatus.ADMISSION_PENDING.name().equals(stored.getStatus())) {
                if (admissionStatus(stored) == FlashSaleAdmissionStatus.ADMISSION_REJECTED) {
                    throw rejectedAdmission(stored.getFailureCode());
                }
                return stored;
            }
            Instant committedAt = activityMapper.currentTime();
            Instant acceptedAt = decision.acceptedAt().truncatedTo(ChronoUnit.MILLIS);
            stored.setStatus(FlashSaleAdmissionStatus.QUEUED.name());
            stored.setRemainingAdmissions(decision.remainingAdmissions());
            stored.setAcceptedAt(acceptedAt);
            stored.setFailureCode(null);
            stored.setUpdatedAt(committedAt);
            requireUpdated(admissionMapper.updateById(stored));
            appendAdmissionAcceptedEvent(activity, stored, committedAt);
            return stored;
        });
    }

    private void rejectPending(
            String requestToken,
            MarketingError error,
            Integer remainingAdmissions) {
        transactionTemplate.executeWithoutResult(status -> {
            FlashSaleAdmissionEntity stored = admissionMapper.selectByTokenForUpdate(requestToken);
            if (stored == null
                    || !FlashSaleAdmissionStatus.ADMISSION_PENDING.name().equals(stored.getStatus())) {
                return;
            }
            Instant now = activityMapper.currentTime();
            stored.setStatus(FlashSaleAdmissionStatus.ADMISSION_REJECTED.name());
            stored.setRemainingAdmissions(
                    remainingAdmissions != null && remainingAdmissions >= 0
                            ? remainingAdmissions
                            : null);
            stored.setFailureCode(error.code());
            stored.setCompletedAt(now);
            stored.setUpdatedAt(now);
            requireUpdated(admissionMapper.updateById(stored));
        });
    }

    private FlashSaleAdmissionView existingAdmissionResult(FlashSaleAdmissionEntity admission) {
        FlashSaleAdmissionStatus admissionStatus = admissionStatus(admission);
        if (admissionStatus == FlashSaleAdmissionStatus.ADMISSION_REJECTED) {
            throw rejectedAdmission(admission.getFailureCode());
        }
        return admissionView(admission);
    }

    private FlashSaleAdmissionStatus admissionStatus(FlashSaleAdmissionEntity admission) {
        String status = admission.getStatus();
        if (status == null) {
            throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE);
        }
        try {
            return FlashSaleAdmissionStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE, exception);
        }
    }

    private MarketingException rejectedAdmission(String failureCode) {
        if (MarketingError.FLASH_SALE_SOLD_OUT.code().equals(failureCode)) {
            return new MarketingException(MarketingError.FLASH_SALE_SOLD_OUT);
        }
        if (MarketingError.FLASH_SALE_ENDED.code().equals(failureCode)) {
            return new MarketingException(MarketingError.FLASH_SALE_ENDED);
        }
        return new MarketingException(MarketingError.FLASH_SALE_INVALID_STATE);
    }

    private void requireSameAdmission(
            FlashSaleAdmissionEntity stored,
            FlashSaleAdmissionEntity expected) {
        if (stored == null
                || !stored.getActivityNo().equals(expected.getActivityNo())
                || !stored.getUserId().equals(expected.getUserId())
                || !stored.getAddressId().equals(expected.getAddressId())
                || !stored.getRequestHash().equals(expected.getRequestHash())) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void preheat(
            FlashSaleActivityEntity activity,
            int admittedCount,
            Instant now) {
        if (admittedCount < 0 || admittedCount > activity.getAdmissionLimit()) {
            throw new FlashSaleAdmissionStoreException(
                    "Persisted flash-sale admissions exceed the configured limit");
        }
        admissionStore.preheat(
                new FlashSaleAdmissionStore.Activity(
                        activity.getActivityNo(),
                        activity.getAdmissionLimit(),
                        admittedCount,
                        activity.getStartsAt(),
                        activity.getEndsAt(),
                        admissionProperties.resultRetention()),
                now);
    }

    private void appendAdmissionAcceptedEvent(
            FlashSaleActivityEntity activity,
            FlashSaleAdmissionEntity admission,
            Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "FlashSaleAdmissionAccepted");
        payload.put("aggregateType", "FlashSaleAdmission");
        payload.put("aggregateId", admission.getRequestToken());
        payload.put("aggregateVersion", admission.getVersion());
        payload.put("occurredAt", now);
        payload.put("producer", "marketing-service");
        payload.put("payloadVersion", 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestToken", admission.getRequestToken());
        data.put("activityNo", admission.getActivityNo());
        data.put("userId", admission.getUserId());
        data.put("addressId", admission.getAddressId());
        data.put("productId", activity.getProductId());
        data.put("skuId", activity.getSkuId());
        data.put("salePrice", activity.getSalePrice());
        data.put("acceptedAt", admission.getAcceptedAt());
        data.put("activityEndsAt", activity.getEndsAt());
        payload.put("payload", data);

        FlashSaleOutboxEventEntity event = new FlashSaleOutboxEventEntity();
        event.setId(eventId);
        event.setEventType("FlashSaleAdmissionAccepted");
        event.setAggregateType("FlashSaleAdmission");
        event.setAggregateId(admission.getRequestToken());
        event.setAggregateVersion(admission.getVersion());
        event.setPayload(writeJson(payload));
        event.setStatus(OutboxStatus.PENDING.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxMapper.insert(event);
    }

    private String admissionHash(String activityNo, Long userId, Long addressId) {
        String canonical = activityNo + "|" + userId + "|" + addressId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize flash-sale event", exception);
        }
    }
}
