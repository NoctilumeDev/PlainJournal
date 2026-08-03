import type { ApiClient, BusinessId } from "./api";

export interface AnalyticsDailySummary {
  businessDate: string;
  createdOrderCount: number;
  createdOrderAmount: number;
  paymentCount: number;
  paymentAmount: number;
  completedOrderCount: number;
  completedOrderAmount: number;
  closedOrderCount: number;
  afterSaleCount: number;
  afterSaleAmount: number;
  refundCount: number;
  refundAmount: number;
  updatedAt: string;
}

export interface AnalyticsProductSummary {
  productId: BusinessId;
  productTitle: string;
  completedOrderCount: number;
  unitsSold: number;
  netRevenue: number;
  revenueCoveredOrderCount: number;
}

export interface AnalyticsOverviewTotals {
  createdOrderCount: number;
  createdOrderAmount: number;
  paymentCount: number;
  paymentAmount: number;
  completedOrderCount: number;
  completedOrderAmount: number;
  closedOrderCount: number;
  afterSaleCount: number;
  afterSaleAmount: number;
  refundCount: number;
  refundAmount: number;
  uniqueCustomers: number;
}

export interface AnalyticsProjectionFreshness {
  sourceEventCount: number;
  lastConsumedAt: string | null;
  generatedAt: string;
}

export interface AnalyticsDashboard {
  from: string;
  to: string;
  totals: AnalyticsOverviewTotals;
  daily: AnalyticsDailySummary[];
  topProducts: AnalyticsProductSummary[];
  freshness: AnalyticsProjectionFreshness;
}

export interface AnalyticsProjectionIssue {
  projection: string;
  issueType: string;
  key: string;
  expected: string | null;
  actual: string | null;
}

export interface AnalyticsReconciliation {
  from: string;
  to: string;
  checkedDailyRows: number;
  checkedProductRows: number;
  issueCount: number;
  saturated: boolean;
  issues: AnalyticsProjectionIssue[];
  generatedAt: string;
}

export interface AnalyticsRebuildInput {
  commandId: string;
  reason: string;
  from: string;
  to: string;
}

export interface AnalyticsRebuildResult {
  commandId: string;
  operatorId: BusinessId;
  reason: string;
  from: string;
  to: string;
  sourceEventCount: number;
  beforeIssueCount: number;
  afterIssueCount: number;
  createdAt: string;
}

export interface AnalyticsApi {
  overview(from: string, to: string, productLimit?: number): Promise<AnalyticsDashboard>;
  reconciliation(from: string, to: string): Promise<AnalyticsReconciliation>;
  rebuild(input: AnalyticsRebuildInput): Promise<AnalyticsRebuildResult>;
}

export function createAnalyticsApi(client: ApiClient): AnalyticsApi {
  return {
    overview(from, to, productLimit = 10) {
      const query = new URLSearchParams({
        from,
        to,
        productLimit: String(productLimit),
      });
      return client.request<AnalyticsDashboard>(
        `/api/v1/analytics/overview?${query.toString()}`,
      );
    },
    reconciliation(from, to) {
      const query = new URLSearchParams({ from, to });
      return client.request<AnalyticsReconciliation>(
        `/api/v1/analytics/admin/reconciliation?${query.toString()}`,
      );
    },
    rebuild(input) {
      return client.request<AnalyticsRebuildResult>(
        "/api/v1/analytics/admin/rebuild",
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
  };
}
