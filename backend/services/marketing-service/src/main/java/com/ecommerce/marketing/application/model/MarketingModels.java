package com.ecommerce.marketing.application.model;

import com.ecommerce.marketing.domain.BenefitType;
import com.ecommerce.marketing.domain.RegionLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MarketingModels {

    private MarketingModels() {
    }

    public record RegionRestriction(RegionLevel level, String regionCode) {
    }

    public record CreateRuleCommand(
            String ruleCode,
            String name,
            BenefitType benefitType,
            BigDecimal thresholdAmount,
            BigDecimal discountAmount,
            int stackOrder,
            Instant validFrom,
            Instant validUntil,
            List<RegionRestriction> regions
    ) {
        public CreateRuleCommand {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    public record RuleView(
            String ruleCode,
            String name,
            BenefitType benefitType,
            BigDecimal thresholdAmount,
            BigDecimal discountAmount,
            int stackOrder,
            String status,
            Instant validFrom,
            Instant validUntil,
            List<RegionRestriction> regions,
            int version
    ) {
        public RuleView {
            regions = List.copyOf(regions);
        }
    }

    public record GrantBenefitCommand(Long userId, String ruleCode, String grantKey) {
    }

    public record BenefitView(
            String benefitNo,
            Long userId,
            String ruleCode,
            BenefitType benefitType,
            BigDecimal thresholdAmount,
            BigDecimal discountAmount,
            String status,
            String lockedOrderNo,
            String redeemedOrderNo,
            Instant validFrom,
            Instant validUntil,
            List<RegionRestriction> regions
    ) {
        public BenefitView {
            regions = List.copyOf(regions);
        }
    }

    public record PricingLine(int lineNo, Long skuId, BigDecimal lineAmount) {
    }

    public record DeliveryRegion(String provinceCode, String cityCode, String districtCode) {
    }

    public record LockPricingCommand(
            String orderNo,
            Long userId,
            BigDecimal originalAmount,
            DeliveryRegion deliveryRegion,
            List<PricingLine> lines,
            List<String> benefitNos
    ) {
        public LockPricingCommand {
            lines = List.copyOf(lines);
            benefitNos = benefitNos == null ? List.of() : List.copyOf(benefitNos);
        }
    }

    public record DiscountAllocation(
            int lineNo,
            Long skuId,
            String benefitNo,
            String ruleCode,
            BenefitType benefitType,
            BigDecimal discountAmount
    ) {
    }

    public record AppliedBenefit(
            String benefitNo,
            String ruleCode,
            BenefitType benefitType,
            BigDecimal discountAmount,
            List<DiscountAllocation> allocations
    ) {
        public AppliedBenefit {
            allocations = List.copyOf(allocations);
        }
    }

    public record PricingLockView(
            String lockNo,
            String orderNo,
            Long userId,
            BigDecimal originalAmount,
            BigDecimal couponDiscount,
            BigDecimal redPacketDiscount,
            BigDecimal subsidyDiscount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            String status,
            List<AppliedBenefit> appliedBenefits,
            Instant lockedAt,
            Instant releasedAt,
            Instant redeemedAt,
            int version
    ) {
        public PricingLockView {
            appliedBenefits = List.copyOf(appliedBenefits);
        }
    }
}
