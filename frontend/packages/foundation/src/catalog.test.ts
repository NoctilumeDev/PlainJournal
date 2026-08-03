import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createCatalogApi } from "./catalog";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-24T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("catalog review api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses the dedicated search projection contract and preserves degradation metadata", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({
        items: [],
        page: 2,
        size: 30,
        matchedTotal: 0,
        source: "MYSQL_FALLBACK",
        degraded: true,
      });
    }));

    const api = createCatalogApi(createApiClient());
    const result = await api.searchProducts({
      q: "  通勤 包  ",
      page: 2,
      size: 30,
      categoryId: "CAT:1",
    });

    const url = new URL(requests[0]?.url ?? "/invalid", "http://localhost");
    expect(url.pathname).toContain("/catalog/search/products");
    expect(url.searchParams.get("q")).toBe("通勤 包");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("30");
    expect(url.searchParams.get("categoryId")).toBe("CAT:1");
    expect(result.source).toBe("MYSQL_FALLBACK");
    expect(result.degraded).toBe(true);
  });

  it("encodes public and customer review paths with stable idempotency", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({});
    }));

    const api = createCatalogApi(createApiClient());
    await api.reviewSummary("P:2026/07");
    await api.productReviews("P:2026/07", 2, 30);
    await api.reviewEligibilities("ORD:2026/07");
    await api.createReview({
      eligibilityId: "ELIG:1",
      rating: 5,
      content: "符合订单快照。",
      anonymous: false,
    }, "review:command:1");
    await api.likeReview("REV:1");
    await api.unlikeReview("REV:1");
    await api.reportReview("REV:1", {
      reasonCode: "OTHER",
      detail: "需要平台复核。",
    });

    expect(requests[0]?.url).toContain(
      "/catalog/products/P%3A2026%2F07/review-summary",
    );
    expect(requests[1]?.url).toContain(
      "/catalog/products/P%3A2026%2F07/reviews?page=2&size=30",
    );
    expect(requests[2]?.url).toContain(
      "/catalog/review-eligibilities?orderNo=ORD%3A2026%2F07",
    );
    expect(new Headers(requests[3]?.init?.headers).get("Idempotency-Key"))
      .toBe("review:command:1");
    expect(requests[4]?.init?.method).toBe("POST");
    expect(requests[5]?.init?.method).toBe("DELETE");
    expect(requests[6]?.url).toContain("/catalog/reviews/REV%3A1/reports");
  });

  it("keeps reply and moderation commands on admin owner-domain paths", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({});
    }));

    const api = createCatalogApi(createApiClient());
    await api.adminReviewReports("OPEN", 1, 25);
    await api.replyReview("REV:1", "平台回复", "reply:command:1");
    await api.resolveReviewReport("REPORT:1", {
      commandId: "moderate:command:1",
      resolution: "UPHELD",
      reason: "核对事实后确认举报成立。",
    });

    expect(requests[0]?.url).toContain(
      "/catalog/admin/reviews/reports?page=1&size=25&status=OPEN",
    );
    expect(requests[1]?.url).toContain(
      "/catalog/admin/reviews/REV%3A1/reply",
    );
    expect(new Headers(requests[1]?.init?.headers).get("Idempotency-Key"))
      .toBe("reply:command:1");
    expect(requests[2]?.url).toContain(
      "/catalog/admin/reviews/reports/REPORT%3A1/resolve",
    );
  });
});
