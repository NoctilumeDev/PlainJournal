import type { ApiClient, BusinessId } from "./api";

export type BenefitType = "COUPON" | "RED_PACKET" | "SUBSIDY";

export interface RegionRestriction {
  level: "PROVINCE" | "CITY" | "DISTRICT";
  regionCode: string;
}

export interface Benefit {
  benefitNo: string;
  userId: BusinessId;
  ruleCode: string;
  benefitType: BenefitType;
  thresholdAmount: string | number;
  discountAmount: string | number;
  status: string;
  lockedOrderNo: string | null;
  redeemedOrderNo: string | null;
  validFrom: string;
  validUntil: string;
  regions: RegionRestriction[];
}

export interface DeliveryRegion {
  provinceCode: string;
  cityCode: string;
  districtCode: string;
}

export interface PricingPreviewLine {
  lineNo: number;
  skuId: BusinessId;
  lineAmount: string;
}

export interface PricingPreviewInput {
  originalAmount: string;
  deliveryRegion: DeliveryRegion;
  lines: PricingPreviewLine[];
  benefitNos: string[];
}

export interface DiscountAllocation {
  lineNo: number;
  skuId: BusinessId;
  benefitNo: string;
  ruleCode: string;
  benefitType: BenefitType;
  discountAmount: string | number;
}

export interface AppliedBenefit {
  benefitNo: string;
  ruleCode: string;
  benefitType: BenefitType;
  discountAmount: string | number;
  allocations: DiscountAllocation[];
}

export interface PricingPreview {
  originalAmount: string | number;
  couponDiscount: string | number;
  redPacketDiscount: string | number;
  subsidyDiscount: string | number;
  discountAmount: string | number;
  payableAmount: string | number;
  appliedBenefits: AppliedBenefit[];
  calculatedAt: string;
}

export interface MarketingRule {
  ruleCode: string;
  name: string;
  benefitType: BenefitType;
  thresholdAmount: string | number;
  discountAmount: string | number;
  stackOrder: number;
  validFrom: string;
  validUntil: string;
  status: string;
  regions: RegionRestriction[];
  version: number;
}

export interface CreateMarketingRuleInput {
  ruleCode: string;
  name: string;
  benefitType: BenefitType;
  thresholdAmount: string;
  discountAmount: string;
  stackOrder: number;
  validFrom: string;
  validUntil: string;
  regions: RegionRestriction[];
}

export interface MarketingApi {
  benefits(): Promise<Benefit[]>;
  previewPricing(input: PricingPreviewInput): Promise<PricingPreview>;
  createRule(input: CreateMarketingRuleInput): Promise<MarketingRule>;
  grantBenefit(userId: BusinessId, ruleCode: string, grantKey: string): Promise<Benefit>;
}

export function createMarketingApi(client: ApiClient): MarketingApi {
  return {
    benefits() {
      return client.request<Benefit[]>("/api/v1/marketing/benefits");
    },
    previewPricing(input) {
      return client.request<PricingPreview>("/api/v1/marketing/pricing-previews", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    createRule(input) {
      return client.request<MarketingRule>("/api/v1/marketing/admin/rules", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    grantBenefit(userId, ruleCode, grantKey) {
      return client.request<Benefit>("/api/v1/marketing/admin/benefits", {
        method: "POST",
        body: JSON.stringify({ userId, ruleCode, grantKey }),
      });
    },
  };
}
