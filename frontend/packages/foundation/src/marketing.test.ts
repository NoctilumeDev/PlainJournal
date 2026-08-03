import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createMarketingApi } from "./marketing";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("marketing admin api", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("uses explicit rule creation and idempotent benefit grant contracts", async () => {
    const requests: Array<{ url: string; body: BodyInit | null | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), body: init?.body });
      return success({});
    }));

    const api = createMarketingApi(createApiClient());
    await api.createRule({
      ruleCode: "M4-COUPON-10",
      name: "M4 验收优惠券",
      benefitType: "COUPON",
      thresholdAmount: "100.00",
      discountAmount: "10.00",
      stackOrder: 0,
      validFrom: "2026-07-21T00:00:00Z",
      validUntil: "2026-08-21T00:00:00Z",
      regions: [],
    });
    await api.grantBenefit(
      "2079000000000000001",
      "M4-COUPON-10",
      "m4-grant:00000000-0000-0000-0000-000000000001",
    );

    expect(requests[0]?.url).toContain("/marketing/admin/rules");
    expect(requests[0]?.body).toContain("\"stackOrder\":0");
    expect(requests[1]?.url).toContain("/marketing/admin/benefits");
    expect(requests[1]?.body).toContain("m4-grant:");
  });
});
