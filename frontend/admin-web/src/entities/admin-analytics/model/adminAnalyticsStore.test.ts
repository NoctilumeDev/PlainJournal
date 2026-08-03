import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { AnalyticsDashboard } from "@plain-journal/foundation";

import {
  useAdminAnalyticsStore,
  type AdminAnalyticsAccessContext,
} from "./adminAnalyticsStore";

const ACCESS: AdminAnalyticsAccessContext = {
  authorized: true,
  operatorId: "2088000000000000001",
  accessToken: "operator-token",
};
const OTHER_ACCESS: AdminAnalyticsAccessContext = {
  authorized: true,
  operatorId: "2088000000000000002",
  accessToken: "admin-token",
};
const FROM = "2026-07-01";
const TO = "2026-07-02";
const PRODUCT_ID = "2088000000000000101";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function dashboardFixture(
  overrides: Partial<AnalyticsDashboard> = {},
): AnalyticsDashboard {
  return {
    from: FROM,
    to: TO,
    totals: {
      createdOrderCount: 12,
      createdOrderAmount: 2268,
      paymentCount: 10,
      paymentAmount: 1890,
      completedOrderCount: 8,
      completedOrderAmount: 1512,
      closedOrderCount: 2,
      afterSaleCount: 1,
      afterSaleAmount: 189,
      refundCount: 1,
      refundAmount: 189,
      uniqueCustomers: 9,
    },
    daily: [{
      businessDate: FROM,
      createdOrderCount: 5,
      createdOrderAmount: 945,
      paymentCount: 4,
      paymentAmount: 756,
      completedOrderCount: 3,
      completedOrderAmount: 567,
      closedOrderCount: 1,
      afterSaleCount: 0,
      afterSaleAmount: 0,
      refundCount: 0,
      refundAmount: 0,
      updatedAt: "2026-07-02T00:00:00Z",
    }, {
      businessDate: TO,
      createdOrderCount: 7,
      createdOrderAmount: 1323,
      paymentCount: 6,
      paymentAmount: 1134,
      completedOrderCount: 5,
      completedOrderAmount: 945,
      closedOrderCount: 1,
      afterSaleCount: 1,
      afterSaleAmount: 189,
      refundCount: 1,
      refundAmount: 189,
      updatedAt: "2026-07-03T00:00:00Z",
    }],
    topProducts: [{
      productId: PRODUCT_ID,
      productTitle: "青荷帆布通勤袋",
      completedOrderCount: 6,
      unitsSold: 8,
      netRevenue: 1512,
      revenueCoveredOrderCount: 6,
    }],
    freshness: {
      sourceEventCount: 34,
      lastConsumedAt: "2026-07-03T00:00:00Z",
      generatedAt: "2026-07-03T00:00:05Z",
    },
    ...overrides,
  };
}

describe("admin analytics entity", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads the exact bounded projection with bearer authorization and string product identity", async () => {
    const requests: Array<{ url: string; authorization: string | null }> = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const headers = new Headers(init?.headers);
      requests.push({
        url: String(input),
        authorization: headers.get("Authorization"),
      });
      return success(dashboardFixture());
    }));

    const store = useAdminAnalyticsStore();
    store.range.from = FROM;
    store.range.to = TO;
    await store.load(ACCESS);

    expect(requests).toEqual([{
      url: "/api/v1/analytics/overview?from=2026-07-01&to=2026-07-02&productLimit=8",
      authorization: "Bearer operator-token",
    }]);
    expect(store.dashboard?.topProducts[0]?.productId).toBe(PRODUCT_ID);
    expect(store.recentDaily.map((summary) => summary.businessDate))
      .toEqual([TO, FROM]);
    expect(store.error).toBeNull();
  });

  it("rejects numeric or duplicated product identities without replacing known facts", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      if (attempts === 1) {
        return success(dashboardFixture());
      }
      const product = dashboardFixture().topProducts[0]!;
      return success(dashboardFixture({
        topProducts: [
          {
            ...product,
            productId: 2088000000000000101 as unknown as string,
          },
        ],
      }));
    }));

    const store = useAdminAnalyticsStore();
    store.range.from = FROM;
    store.range.to = TO;
    await store.load(ACCESS);
    await store.load(ACCESS);

    expect(store.dashboard?.topProducts[0]?.productId).toBe(PRODUCT_ID);
    expect(store.error).toContain("字符串身份契约");
  });

  it("keeps the newest range when an older request finishes later", async () => {
    let resolveFirst!: (response: Response) => void;
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(() => {
      attempts += 1;
      if (attempts === 1) {
        return new Promise<Response>((resolve) => {
          resolveFirst = resolve;
        });
      }
      return Promise.resolve(success(dashboardFixture({
        from: "2026-07-02",
        to: "2026-07-02",
        daily: [dashboardFixture().daily[1]!],
        totals: {
          ...dashboardFixture().totals,
          createdOrderCount: 7,
        },
      })));
    }));

    const store = useAdminAnalyticsStore();
    store.range.from = FROM;
    store.range.to = TO;
    const first = store.load(ACCESS);
    store.range.from = TO;
    const second = store.load(ACCESS);
    await second;
    resolveFirst(success(dashboardFixture()));
    await first;

    expect(store.dashboard?.from).toBe(TO);
    expect(store.dashboard?.totals.createdOrderCount).toBe(7);
  });

  it("drops a completed projection after the operator or token generation changes", async () => {
    let resolveRequest!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveRequest = resolve;
      })));

    const store = useAdminAnalyticsStore();
    store.range.from = FROM;
    store.range.to = TO;
    const request = store.load(ACCESS);
    store.synchronizeAccess(OTHER_ACCESS);
    resolveRequest(success(dashboardFixture()));
    await request;

    expect(store.dashboard).toBeNull();
    expect(store.error).toBeNull();
  });

  it("preserves the last known projection when a refresh receives 503", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      return attempts === 1
        ? success(dashboardFixture())
        : failure(
            503,
            "SERVICE_UNAVAILABLE",
            "analytics projection unavailable",
          );
    }));

    const store = useAdminAnalyticsStore();
    store.range.from = FROM;
    store.range.to = TO;
    await store.load(ACCESS);
    const refreshedAt = store.refreshedAt;
    await store.load(ACCESS);

    expect(store.dashboard).toEqual(dashboardFixture());
    expect(store.refreshedAt).toBe(refreshedAt);
    expect(store.error).toBe("analytics projection unavailable");
  });

  it("rejects an inverted or oversized range before sending a request", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const store = useAdminAnalyticsStore();

    store.range.from = "2026-07-03";
    store.range.to = "2026-07-02";
    await store.load(ACCESS);
    expect(store.error).toContain("开始日期不能晚于结束日期");

    store.range.from = "2025-01-01";
    store.range.to = "2026-07-02";
    await store.load(ACCESS);
    expect(store.error).toContain("不能超过 366 天");
    expect(fetch).not.toHaveBeenCalled();
  });
});
