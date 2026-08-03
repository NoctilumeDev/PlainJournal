package com.ecommerce.marketing.application.model;

import com.ecommerce.marketing.domain.BenefitType;
import com.ecommerce.marketing.domain.RegionLevel;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
            @JsonSerialize(using = ToStringSerializer.class)
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
            lines = immutableCopyAllowingNulls(lines);
            benefitNos = benefitNos == null ? List.of() : immutableCopyAllowingNulls(benefitNos);
        }
    }

    public record DiscountAllocation(
            int lineNo,
            @JsonSerialize(using = ToStringSerializer.class)
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

    public record PreviewPricingCommand(
            Long userId,
            BigDecimal originalAmount,
            DeliveryRegion deliveryRegion,
            List<PricingLine> lines,
            List<String> benefitNos
    ) {
        public PreviewPricingCommand {
            lines = immutableCopyAllowingNulls(lines);
            benefitNos = benefitNos == null ? List.of() : immutableCopyAllowingNulls(benefitNos);
        }
    }

    public record PricingPreviewView(
            BigDecimal originalAmount,
            BigDecimal couponDiscount,
            BigDecimal redPacketDiscount,
            BigDecimal subsidyDiscount,
            BigDecimal discountAmount,
            BigDecimal payableAmount,
            List<AppliedBenefit> appliedBenefits,
            Instant calculatedAt
    ) {
        public PricingPreviewView {
            appliedBenefits = List.copyOf(appliedBenefits);
        }
    }

    public record PricingLockView(
            String lockNo,
            String orderNo,
            @JsonSerialize(using = ToStringSerializer.class)
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

    private static <T> List<T> immutableCopyAllowingNulls(List<T> values) {
        return values == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
