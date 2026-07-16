package com.ecommerce.trade.application.port;

import java.math.BigDecimal;
import java.util.List;

public interface MarketingPort {

    PricingLock lockPricing(PricingCommand command);

    record PricingCommand(
            String orderNo,
            Long userId,
            BigDecimal originalAmount,
            DeliveryRegion deliveryRegion,
            List<PricingLine> lines,
            List<String> benefitNos
    ) {
        public PricingCommand {
            lines = List.copyOf(lines);
            benefitNos = List.copyOf(benefitNos);
        }
    }

    record DeliveryRegion(String provinceCode, String cityCode, String districtCode) {
    }

    record PricingLine(int lineNo, Long skuId, BigDecimal lineAmount) {
    }

    record PricingLock(
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
            List<AppliedBenefit> appliedBenefits
    ) {
        public PricingLock {
            appliedBenefits = List.copyOf(appliedBenefits);
        }
    }

    record AppliedBenefit(
            String benefitNo,
            String ruleCode,
            String benefitType,
            BigDecimal discountAmount,
            List<DiscountAllocation> allocations
    ) {
        public AppliedBenefit {
            allocations = List.copyOf(allocations);
        }
    }

    record DiscountAllocation(
            int lineNo,
            Long skuId,
            String benefitNo,
            String ruleCode,
            String benefitType,
            BigDecimal discountAmount
    ) {
    }

    final class PricingRejectedException extends RuntimeException {
        public PricingRejectedException(String message) {
            super(message);
        }
    }
}
