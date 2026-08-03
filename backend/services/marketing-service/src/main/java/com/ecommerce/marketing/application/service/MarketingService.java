package com.ecommerce.marketing.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.marketing.application.exception.MarketingError;
import com.ecommerce.marketing.application.exception.MarketingException;
import com.ecommerce.marketing.application.model.MarketingModels.AppliedBenefit;
import com.ecommerce.marketing.application.model.MarketingModels.BenefitView;
import com.ecommerce.marketing.application.model.MarketingModels.CreateRuleCommand;
import com.ecommerce.marketing.application.model.MarketingModels.DeliveryRegion;
import com.ecommerce.marketing.application.model.MarketingModels.DiscountAllocation;
import com.ecommerce.marketing.application.model.MarketingModels.GrantBenefitCommand;
import com.ecommerce.marketing.application.model.MarketingModels.LockPricingCommand;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLine;
import com.ecommerce.marketing.application.model.MarketingModels.PricingLockView;
import com.ecommerce.marketing.application.model.MarketingModels.PricingPreviewView;
import com.ecommerce.marketing.application.model.MarketingModels.PreviewPricingCommand;
import com.ecommerce.marketing.application.model.MarketingModels.RegionRestriction;
import com.ecommerce.marketing.application.model.MarketingModels.RuleView;
import com.ecommerce.marketing.domain.BenefitStatus;
import com.ecommerce.marketing.domain.BenefitType;
import com.ecommerce.marketing.domain.PricingLockStatus;
import com.ecommerce.marketing.domain.RegionLevel;
import com.ecommerce.marketing.domain.RuleStatus;
import com.ecommerce.marketing.infrastructure.persistence.entity.MarketingRuleEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.PricingLockAllocationEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.PricingLockBenefitEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.PricingLockEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.RuleRegionEntity;
import com.ecommerce.marketing.infrastructure.persistence.entity.UserBenefitEntity;
import com.ecommerce.marketing.infrastructure.persistence.mapper.MarketingRuleMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.PricingLockAllocationMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.PricingLockBenefitMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.PricingLockMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.RuleRegionMapper;
import com.ecommerce.marketing.infrastructure.persistence.mapper.UserBenefitMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MarketingService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final MarketingRuleMapper ruleMapper;
    private final RuleRegionMapper regionMapper;
    private final UserBenefitMapper benefitMapper;
    private final PricingLockMapper lockMapper;
    private final PricingLockBenefitMapper lockBenefitMapper;
    private final PricingLockAllocationMapper allocationMapper;
    private final ObjectMapper objectMapper;

    public MarketingService(
            MarketingRuleMapper ruleMapper,
            RuleRegionMapper regionMapper,
            UserBenefitMapper benefitMapper,
            PricingLockMapper lockMapper,
            PricingLockBenefitMapper lockBenefitMapper,
            PricingLockAllocationMapper allocationMapper,
            ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.regionMapper = regionMapper;
        this.benefitMapper = benefitMapper;
        this.lockMapper = lockMapper;
        this.lockBenefitMapper = lockBenefitMapper;
        this.allocationMapper = allocationMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RuleView createRule(CreateRuleCommand command) {
        BigDecimal threshold = money(command.thresholdAmount(), true);
        BigDecimal discount = money(command.discountAmount(), false);
        if (command.validFrom() == null || command.validUntil() == null
                || !command.validUntil().isAfter(command.validFrom())
                || command.stackOrder() < 0
                || command.regions().stream().map(region -> region.level() + ":" + region.regionCode())
                .distinct().count() != command.regions().size()) {
            throw new MarketingException(MarketingError.INVALID_RULE);
        }
        command.regions().forEach(region -> {
            if (region.level() == null || !isRegionCode(region.regionCode())) {
                throw new MarketingException(MarketingError.INVALID_RULE);
            }
        });
        Instant now = lockMapper.currentTime();
        MarketingRuleEntity rule = new MarketingRuleEntity();
        rule.setId(IdWorker.getId());
        rule.setRuleCode(command.ruleCode().trim());
        rule.setName(command.name().trim());
        rule.setBenefitType(command.benefitType().name());
        rule.setThresholdAmount(threshold);
        rule.setDiscountAmount(discount);
        rule.setStackOrder(command.stackOrder());
        rule.setStatus(RuleStatus.ACTIVE.name());
        rule.setValidFrom(command.validFrom());
        rule.setValidUntil(command.validUntil());
        rule.setVersion(0);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        ruleMapper.insert(rule);
        for (RegionRestriction restriction : command.regions()) {
            RuleRegionEntity region = new RuleRegionEntity();
            region.setId(IdWorker.getId());
            region.setRuleId(rule.getId());
            region.setRegionLevel(restriction.level().name());
            region.setRegionCode(restriction.regionCode());
            region.setCreatedAt(now);
            regionMapper.insert(region);
        }
        return ruleView(rule);
    }

    @Transactional
    public BenefitView grantBenefit(GrantBenefitCommand command) {
        MarketingRuleEntity rule = ruleMapper.selectByRuleCode(command.ruleCode());
        if (rule == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        Instant now = lockMapper.currentTime();
        long id = IdWorker.getId();
        UserBenefitEntity candidate = new UserBenefitEntity();
        candidate.setId(id);
        candidate.setBenefitNo("BEN" + id);
        candidate.setGrantKey(command.grantKey().trim());
        candidate.setRuleId(rule.getId());
        candidate.setUserId(command.userId());
        candidate.setStatus(BenefitStatus.AVAILABLE.name());
        candidate.setVersion(0);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        benefitMapper.insertOrLockExisting(candidate);
        UserBenefitEntity benefit = benefitMapper.selectByGrantKeyForUpdate(command.userId(), command.grantKey().trim());
        if (benefit == null) {
            throw new MarketingException(MarketingError.CONCURRENT_MODIFICATION);
        }
        if (!benefit.getRuleId().equals(rule.getId())) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
        return benefitView(benefit, rule);
    }

    public List<BenefitView> listBenefits(Long userId) {
        return benefitMapper.selectList(new LambdaQueryWrapper<UserBenefitEntity>()
                        .eq(UserBenefitEntity::getUserId, userId)
                        .orderByDesc(UserBenefitEntity::getCreatedAt))
                .stream().map(benefit -> benefitView(benefit, requireRule(benefit.getRuleId()))).toList();
    }

    @Transactional(readOnly = true)
    public PricingPreviewView previewPricing(PreviewPricingCommand command) {
        ValidatedPricing request = validatePricing(command);
        Instant now = lockMapper.currentTime();
        PricingCalculation calculation = calculatePricing(
                request, loadEligibleBenefits(request, now, false));
        return new PricingPreviewView(
                request.originalAmount(),
                calculation.total(BenefitType.COUPON),
                calculation.total(BenefitType.RED_PACKET),
                calculation.total(BenefitType.SUBSIDY),
                calculation.discountAmount(),
                calculation.payableAmount(),
                calculation.appliedBenefits(),
                now);
    }

    @Transactional
    public PricingLockView lockPricing(LockPricingCommand command) {
        ValidatedPricing request = validatePricing(command);
        String requestHash = requestHash(request);
        Instant now = lockMapper.currentTime();
        long id = IdWorker.getId();
        PricingLockEntity candidate = new PricingLockEntity();
        candidate.setId(id);
        candidate.setLockNo("MKT" + id);
        candidate.setOrderNo(request.orderNo());
        candidate.setUserId(request.userId());
        candidate.setRequestHash(requestHash);
        candidate.setOriginalAmount(request.originalAmount());
        candidate.setDiscountAmount(ZERO);
        candidate.setPayableAmount(request.originalAmount());
        candidate.setStatus(PricingLockStatus.LOCKING.name());
        candidate.setVersion(0);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        lockMapper.insertOrLockExisting(candidate);

        PricingLockEntity lock = lockMapper.selectByOrderNoForUpdate(request.orderNo());
        if (lock == null) {
            throw new MarketingException(MarketingError.CONCURRENT_MODIFICATION);
        }
        if (!MessageDigest.isEqual(lock.getRequestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw new MarketingException(MarketingError.IDEMPOTENCY_CONFLICT);
        }
        if (!PricingLockStatus.LOCKING.name().equals(lock.getStatus())) {
            return lockView(lock);
        }

        PricingCalculation calculation = calculatePricing(
                request, loadEligibleBenefits(request, now, true));
        for (CalculatedBenefit applied : calculation.benefits()) {
            insertAppliedBenefit(lock, applied, now);
            lockUserBenefit(applied.eligible().benefit(), request.orderNo(), now);
        }

        lock.setDiscountAmount(calculation.discountAmount());
        lock.setPayableAmount(calculation.payableAmount());
        lock.setStatus(PricingLockStatus.LOCKED.name());
        lock.setLockedAt(now);
        lock.setUpdatedAt(now);
        requireUpdated(lockMapper.updateById(lock));
        return lockView(lock);
    }

    @Transactional
    public PricingLockView release(String orderNo) {
        return release(requireLock(orderNo), orderNo);
    }

    @Transactional
    public void releaseIfPresent(String orderNo) {
        PricingLockEntity lock = lockMapper.selectByOrderNoForUpdate(orderNo);
        if (lock != null) {
            release(lock, orderNo);
        }
    }

    private PricingLockView release(PricingLockEntity lock, String orderNo) {
        if (PricingLockStatus.RELEASED.name().equals(lock.getStatus())) {
            return lockView(lock);
        }
        if (PricingLockStatus.REDEEMED.name().equals(lock.getStatus())
                || PricingLockStatus.LOCKING.name().equals(lock.getStatus())) {
            throw new MarketingException(MarketingError.INVALID_STATE);
        }
        Instant now = lockMapper.currentTime();
        for (PricingLockBenefitEntity applied : lockBenefits(lock.getId())) {
            UserBenefitEntity benefit = benefitMapper.selectByBenefitNoForUpdate(applied.getBenefitNo());
            if (benefit == null || !BenefitStatus.LOCKED.name().equals(benefit.getStatus())
                    || !orderNo.equals(benefit.getLockedOrderNo())) {
                throw new MarketingException(MarketingError.INVALID_STATE);
            }
            benefit.setStatus(BenefitStatus.AVAILABLE.name());
            benefit.setLockedOrderNo(null);
            benefit.setLockedAt(null);
            benefit.setUpdatedAt(now);
            requireUpdated(benefitMapper.updateById(benefit));
        }
        lock.setStatus(PricingLockStatus.RELEASED.name());
        lock.setReleasedAt(now);
        lock.setUpdatedAt(now);
        requireUpdated(lockMapper.updateById(lock));
        return lockView(lock);
    }

    @Transactional
    public PricingLockView redeem(String orderNo) {
        return redeem(requireLock(orderNo), orderNo);
    }

    @Transactional
    public void redeemIfPresent(String orderNo) {
        PricingLockEntity lock = lockMapper.selectByOrderNoForUpdate(orderNo);
        if (lock != null) {
            redeem(lock, orderNo);
        }
    }

    private PricingLockView redeem(PricingLockEntity lock, String orderNo) {
        if (PricingLockStatus.REDEEMED.name().equals(lock.getStatus())) {
            return lockView(lock);
        }
        if (!PricingLockStatus.LOCKED.name().equals(lock.getStatus())) {
            throw new MarketingException(MarketingError.INVALID_STATE);
        }
        Instant now = lockMapper.currentTime();
        for (PricingLockBenefitEntity applied : lockBenefits(lock.getId())) {
            UserBenefitEntity benefit = benefitMapper.selectByBenefitNoForUpdate(applied.getBenefitNo());
            if (benefit == null || !BenefitStatus.LOCKED.name().equals(benefit.getStatus())
                    || !orderNo.equals(benefit.getLockedOrderNo())) {
                throw new MarketingException(MarketingError.INVALID_STATE);
            }
            benefit.setStatus(BenefitStatus.REDEEMED.name());
            benefit.setLockedOrderNo(null);
            benefit.setLockedAt(null);
            benefit.setRedeemedOrderNo(orderNo);
            benefit.setRedeemedAt(now);
            benefit.setUpdatedAt(now);
            requireUpdated(benefitMapper.updateById(benefit));
        }
        lock.setStatus(PricingLockStatus.REDEEMED.name());
        lock.setRedeemedAt(now);
        lock.setUpdatedAt(now);
        requireUpdated(lockMapper.updateById(lock));
        return lockView(lock);
    }

    public PricingLockView getLock(String orderNo) {
        PricingLockEntity lock = lockMapper.selectOne(new LambdaQueryWrapper<PricingLockEntity>()
                .eq(PricingLockEntity::getOrderNo, orderNo));
        if (lock == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return lockView(lock);
    }

    private ValidatedPricing validatePricing(LockPricingCommand command) {
        if (command == null || command.orderNo() == null || command.orderNo().isBlank()) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        return validatePricing(command.orderNo().trim(), command.userId(), command.originalAmount(),
                command.deliveryRegion(), command.lines(), command.benefitNos());
    }

    private ValidatedPricing validatePricing(PreviewPricingCommand command) {
        if (command == null) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        return validatePricing(null, command.userId(), command.originalAmount(),
                command.deliveryRegion(), command.lines(), command.benefitNos());
    }

    private ValidatedPricing validatePricing(
            String orderNo,
            Long userId,
            BigDecimal originalAmount,
            DeliveryRegion deliveryRegion,
            List<PricingLine> requestedLines,
            List<String> requestedBenefitNos) {
        BigDecimal original = money(originalAmount, false);
        List<String> benefitNos = requestedBenefitNos == null ? List.of() : requestedBenefitNos;
        if (userId == null || userId <= 0 || requestedLines == null || requestedLines.isEmpty()
                || requestedLines.stream().anyMatch(Objects::isNull)
                || requestedLines.size() > 100 || benefitNos.size() > BenefitType.values().length
                || benefitNos.stream().anyMatch(benefitNo ->
                benefitNo == null || benefitNo.isBlank() || benefitNo.length() > 64)
                || new HashSet<>(benefitNos).size() != benefitNos.size()) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        List<PricingLine> lines = requestedLines.stream().map(line ->
                new PricingLine(line.lineNo(), line.skuId(), money(line.lineAmount(), false))).toList();
        if (lines.stream().anyMatch(line -> line.lineNo() <= 0 || line.skuId() == null || line.skuId() <= 0)
                || lines.stream().map(PricingLine::lineNo).distinct().count() != lines.size()
                || lines.stream().map(PricingLine::lineAmount).reduce(ZERO, BigDecimal::add)
                .compareTo(original) != 0) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        DeliveryRegion region = deliveryRegion;
        if (region == null || !optionalRegionCode(region.provinceCode())
                || !optionalRegionCode(region.cityCode()) || !optionalRegionCode(region.districtCode())) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        return new ValidatedPricing(orderNo, userId, original, region,
                lines, benefitNos.stream().map(String::trim).sorted().toList());
    }

    private List<EligibleBenefit> loadEligibleBenefits(
            ValidatedPricing request,
            Instant now,
            boolean lockRows) {
        List<EligibleBenefit> result = new ArrayList<>();
        Set<BenefitType> usedTypes = new HashSet<>();
        for (String benefitNo : request.benefitNos()) {
            UserBenefitEntity benefit = lockRows
                    ? benefitMapper.selectByBenefitNoForUpdate(benefitNo)
                    : benefitMapper.selectOne(new LambdaQueryWrapper<UserBenefitEntity>()
                    .eq(UserBenefitEntity::getBenefitNo, benefitNo));
            if (benefit == null || !benefit.getUserId().equals(request.userId())
                    || !(BenefitStatus.AVAILABLE.name().equals(benefit.getStatus())
                    || (lockRows && BenefitStatus.LOCKED.name().equals(benefit.getStatus())
                    && request.orderNo().equals(benefit.getLockedOrderNo())))) {
                throw new MarketingException(MarketingError.BENEFIT_NOT_ELIGIBLE);
            }
            MarketingRuleEntity rule = requireRule(benefit.getRuleId());
            BenefitType type = BenefitType.valueOf(rule.getBenefitType());
            if (!usedTypes.add(type)) {
                throw new MarketingException(MarketingError.DUPLICATE_BENEFIT_TYPE);
            }
            if (!RuleStatus.ACTIVE.name().equals(rule.getStatus())
                    || now.isBefore(rule.getValidFrom()) || !now.isBefore(rule.getValidUntil())
                    || request.originalAmount().compareTo(rule.getThresholdAmount()) < 0
                    || !regionMatches(rule.getId(), request.deliveryRegion())) {
                throw new MarketingException(MarketingError.BENEFIT_NOT_ELIGIBLE);
            }
            result.add(new EligibleBenefit(benefit, rule));
        }
        return result.stream().sorted(Comparator
                .comparingInt((EligibleBenefit item) -> item.rule().getStackOrder())
                .thenComparing(item -> item.benefit().getBenefitNo())).toList();
    }

    private PricingCalculation calculatePricing(
            ValidatedPricing request,
            List<EligibleBenefit> eligible) {
        Map<Integer, Long> remainingCents = new LinkedHashMap<>();
        Map<Integer, PricingLine> linesByNumber = new HashMap<>();
        request.lines().stream().sorted(Comparator.comparingInt(PricingLine::lineNo))
                .forEach(line -> {
                    remainingCents.put(line.lineNo(), cents(line.lineAmount()));
                    linesByNumber.put(line.lineNo(), line);
                });

        EnumMap<BenefitType, BigDecimal> totals = new EnumMap<>(BenefitType.class);
        List<CalculatedBenefit> applied = new ArrayList<>();
        BigDecimal totalDiscount = ZERO;
        for (EligibleBenefit selected : eligible) {
            long remaining = remainingCents.values().stream().mapToLong(Long::longValue).sum();
            if (remaining <= 0) {
                throw new MarketingException(MarketingError.BENEFIT_NOT_ELIGIBLE);
            }
            long appliedCents = Math.min(cents(selected.rule().getDiscountAmount()), remaining);
            Map<Integer, Long> shares = allocate(appliedCents, remainingCents);
            BigDecimal appliedAmount = amount(appliedCents);
            List<DiscountAllocation> allocations = shares.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> {
                        PricingLine line = linesByNumber.get(entry.getKey());
                        return new DiscountAllocation(
                                entry.getKey(),
                                line.skuId(),
                                selected.benefit().getBenefitNo(),
                                selected.rule().getRuleCode(),
                                BenefitType.valueOf(selected.rule().getBenefitType()),
                                amount(entry.getValue()));
                    })
                    .toList();
            AppliedBenefit view = new AppliedBenefit(
                    selected.benefit().getBenefitNo(),
                    selected.rule().getRuleCode(),
                    BenefitType.valueOf(selected.rule().getBenefitType()),
                    appliedAmount,
                    allocations);
            applied.add(new CalculatedBenefit(selected, view));
            totals.merge(view.benefitType(), appliedAmount, BigDecimal::add);
            totalDiscount = totalDiscount.add(appliedAmount);
            shares.forEach((lineNo, share) ->
                    remainingCents.compute(lineNo, (ignored, value) -> value - share));
        }
        return new PricingCalculation(
                totalDiscount,
                request.originalAmount().subtract(totalDiscount),
                totals,
                applied);
    }

    private boolean regionMatches(Long ruleId, DeliveryRegion deliveryRegion) {
        List<RuleRegionEntity> regions = regions(ruleId);
        if (regions.isEmpty()) {
            return true;
        }
        return regions.stream().anyMatch(region -> switch (RegionLevel.valueOf(region.getRegionLevel())) {
            case PROVINCE -> region.getRegionCode().equals(deliveryRegion.provinceCode());
            case CITY -> region.getRegionCode().equals(deliveryRegion.cityCode());
            case DISTRICT -> region.getRegionCode().equals(deliveryRegion.districtCode());
        });
    }

    private Map<Integer, Long> allocate(long discountCents, Map<Integer, Long> capacities) {
        long totalCapacity = capacities.values().stream().mapToLong(Long::longValue).sum();
        if (discountCents <= 0 || discountCents > totalCapacity) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        BigInteger total = BigInteger.valueOf(totalCapacity);
        List<Share> shares = new ArrayList<>();
        long assigned = 0;
        for (Map.Entry<Integer, Long> entry : capacities.entrySet()) {
            BigInteger numerator = BigInteger.valueOf(discountCents).multiply(BigInteger.valueOf(entry.getValue()));
            BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(total);
            long base = quotientAndRemainder[0].longValueExact();
            shares.add(new Share(entry.getKey(), entry.getValue(), base, quotientAndRemainder[1]));
            assigned += base;
        }
        long remainder = discountCents - assigned;
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparingInt(Share::lineNo));
        for (Share share : shares) {
            if (remainder == 0) {
                break;
            }
            if (share.allocated() < share.capacity()) {
                share.increment();
                remainder--;
            }
        }
        if (remainder != 0) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        shares.sort(Comparator.comparingInt(Share::lineNo));
        Map<Integer, Long> result = new LinkedHashMap<>();
        shares.forEach(share -> result.put(share.lineNo(), share.allocated()));
        return result;
    }

    private void insertAppliedBenefit(
            PricingLockEntity lock,
            CalculatedBenefit applied,
            Instant now) {
        EligibleBenefit selected = applied.eligible();
        AppliedBenefit view = applied.view();
        PricingLockBenefitEntity snapshot = new PricingLockBenefitEntity();
        snapshot.setId(IdWorker.getId());
        snapshot.setLockId(lock.getId());
        snapshot.setUserBenefitId(selected.benefit().getId());
        snapshot.setBenefitNo(view.benefitNo());
        snapshot.setRuleCode(view.ruleCode());
        snapshot.setBenefitType(view.benefitType().name());
        snapshot.setDiscountAmount(view.discountAmount());
        snapshot.setCreatedAt(now);
        lockBenefitMapper.insert(snapshot);
        for (DiscountAllocation source : view.allocations()) {
            PricingLockAllocationEntity allocation = new PricingLockAllocationEntity();
            allocation.setId(IdWorker.getId());
            allocation.setLockId(lock.getId());
            allocation.setBenefitNo(source.benefitNo());
            allocation.setRuleCode(source.ruleCode());
            allocation.setBenefitType(source.benefitType().name());
            allocation.setLineNo(source.lineNo());
            allocation.setSkuId(source.skuId());
            allocation.setDiscountAmount(source.discountAmount());
            allocation.setCreatedAt(now);
            allocationMapper.insert(allocation);
        }
    }

    private void lockUserBenefit(UserBenefitEntity benefit, String orderNo, Instant now) {
        benefit.setStatus(BenefitStatus.LOCKED.name());
        benefit.setLockedOrderNo(orderNo);
        benefit.setLockedAt(now);
        benefit.setUpdatedAt(now);
        requireUpdated(benefitMapper.updateById(benefit));
    }

    private PricingLockEntity requireLock(String orderNo) {
        PricingLockEntity lock = lockMapper.selectByOrderNoForUpdate(orderNo);
        if (lock == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return lock;
    }

    private MarketingRuleEntity requireRule(Long ruleId) {
        MarketingRuleEntity rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new MarketingException(MarketingError.RESOURCE_NOT_FOUND);
        }
        return rule;
    }

    private RuleView ruleView(MarketingRuleEntity rule) {
        return new RuleView(rule.getRuleCode(), rule.getName(), BenefitType.valueOf(rule.getBenefitType()),
                rule.getThresholdAmount(), rule.getDiscountAmount(), rule.getStackOrder(), rule.getStatus(),
                rule.getValidFrom(), rule.getValidUntil(), regionViews(rule.getId()), rule.getVersion());
    }

    private BenefitView benefitView(UserBenefitEntity benefit, MarketingRuleEntity rule) {
        return new BenefitView(benefit.getBenefitNo(), benefit.getUserId(), rule.getRuleCode(),
                BenefitType.valueOf(rule.getBenefitType()), rule.getThresholdAmount(), rule.getDiscountAmount(),
                benefit.getStatus(), benefit.getLockedOrderNo(), benefit.getRedeemedOrderNo(),
                rule.getValidFrom(), rule.getValidUntil(), regionViews(rule.getId()));
    }

    private PricingLockView lockView(PricingLockEntity lock) {
        List<PricingLockAllocationEntity> allocations = allocationMapper.selectList(
                new LambdaQueryWrapper<PricingLockAllocationEntity>()
                        .eq(PricingLockAllocationEntity::getLockId, lock.getId())
                        .orderByAsc(PricingLockAllocationEntity::getLineNo));
        Map<String, List<DiscountAllocation>> byBenefit = new HashMap<>();
        for (PricingLockAllocationEntity allocation : allocations) {
            byBenefit.computeIfAbsent(allocation.getBenefitNo(), ignored -> new ArrayList<>()).add(
                    new DiscountAllocation(allocation.getLineNo(), allocation.getSkuId(),
                            allocation.getBenefitNo(), allocation.getRuleCode(),
                            BenefitType.valueOf(allocation.getBenefitType()), allocation.getDiscountAmount()));
        }
        EnumMap<BenefitType, BigDecimal> totals = new EnumMap<>(BenefitType.class);
        List<AppliedBenefit> applied = lockBenefits(lock.getId()).stream().map(snapshot -> {
            BenefitType type = BenefitType.valueOf(snapshot.getBenefitType());
            totals.merge(type, snapshot.getDiscountAmount(), BigDecimal::add);
            return new AppliedBenefit(snapshot.getBenefitNo(), snapshot.getRuleCode(), type,
                    snapshot.getDiscountAmount(), byBenefit.getOrDefault(snapshot.getBenefitNo(), List.of()));
        }).toList();
        return new PricingLockView(lock.getLockNo(), lock.getOrderNo(), lock.getUserId(),
                lock.getOriginalAmount(), totals.getOrDefault(BenefitType.COUPON, ZERO),
                totals.getOrDefault(BenefitType.RED_PACKET, ZERO),
                totals.getOrDefault(BenefitType.SUBSIDY, ZERO), lock.getDiscountAmount(),
                lock.getPayableAmount(), lock.getStatus(), applied, lock.getLockedAt(),
                lock.getReleasedAt(), lock.getRedeemedAt(), lock.getVersion());
    }

    private List<PricingLockBenefitEntity> lockBenefits(Long lockId) {
        return lockBenefitMapper.selectList(new LambdaQueryWrapper<PricingLockBenefitEntity>()
                .eq(PricingLockBenefitEntity::getLockId, lockId)
                .orderByAsc(PricingLockBenefitEntity::getId));
    }

    private List<RuleRegionEntity> regions(Long ruleId) {
        return regionMapper.selectList(new LambdaQueryWrapper<RuleRegionEntity>()
                .eq(RuleRegionEntity::getRuleId, ruleId)
                .orderByAsc(RuleRegionEntity::getRegionLevel)
                .orderByAsc(RuleRegionEntity::getRegionCode));
    }

    private List<RegionRestriction> regionViews(Long ruleId) {
        return regions(ruleId).stream().map(region ->
                new RegionRestriction(RegionLevel.valueOf(region.getRegionLevel()), region.getRegionCode())).toList();
    }

    private String requestHash(ValidatedPricing request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash pricing request", exception);
        }
    }

    private BigDecimal money(BigDecimal value, boolean allowZero) {
        if (value == null) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
        }
        try {
            BigDecimal normalized = value.setScale(2, RoundingMode.UNNECESSARY);
            if (allowZero ? normalized.signum() < 0 : normalized.signum() <= 0) {
                throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST);
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST, exception);
        }
    }

    private long cents(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new MarketingException(MarketingError.INVALID_PRICING_REQUEST, exception);
        }
    }

    private BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private boolean isRegionCode(String code) {
        return code != null && code.matches("\\d{6}");
    }

    private boolean optionalRegionCode(String code) {
        return code == null || isRegionCode(code);
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new MarketingException(MarketingError.CONCURRENT_MODIFICATION);
        }
    }

    private record EligibleBenefit(UserBenefitEntity benefit, MarketingRuleEntity rule) {
    }

    private record CalculatedBenefit(EligibleBenefit eligible, AppliedBenefit view) {
    }

    private record PricingCalculation(
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            EnumMap<BenefitType, BigDecimal> totals,
            List<CalculatedBenefit> benefits
    ) {
        private BigDecimal total(BenefitType type) {
            return totals.getOrDefault(type, ZERO);
        }

        private List<AppliedBenefit> appliedBenefits() {
            return benefits.stream().map(CalculatedBenefit::view).toList();
        }
    }

    private record ValidatedPricing(
            String orderNo,
            Long userId,
            BigDecimal originalAmount,
            DeliveryRegion deliveryRegion,
            List<PricingLine> lines,
            List<String> benefitNos
    ) {
    }

    private static final class Share {
        private final int lineNo;
        private final long capacity;
        private long allocated;
        private final BigInteger remainder;

        private Share(int lineNo, long capacity, long allocated, BigInteger remainder) {
            this.lineNo = lineNo;
            this.capacity = capacity;
            this.allocated = allocated;
            this.remainder = remainder;
        }

        int lineNo() {
            return lineNo;
        }

        long capacity() {
            return capacity;
        }

        long allocated() {
            return allocated;
        }

        BigInteger remainder() {
            return remainder;
        }

        void increment() {
            allocated++;
        }
    }
}
