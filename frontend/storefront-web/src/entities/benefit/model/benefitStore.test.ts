import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  BenefitAccessChangedError,
  type BenefitAccessContext,
  useBenefitsStore,
} from "./benefitStore";

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

describe("customer benefit center", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("counts only Marketing AVAILABLE facts as currently usable", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([
      {
        benefitNo: "BEN-001",
        userId: "2079000000000000999",
        ruleCode: "COUPON-10",
        benefitType: "COUPON",
        thresholdAmount: "100.00",
        discountAmount: "10.00",
        status: "AVAILABLE",
        lockedOrderNo: null,
        redeemedOrderNo: null,
        validFrom: "2026-07-21T00:00:00Z",
        validUntil: "2026-08-21T00:00:00Z",
        regions: [],
      },
      {
        benefitNo: "BEN-002",
        userId: "2079000000000000999",
        ruleCode: "RED-5",
        benefitType: "RED_PACKET",
        thresholdAmount: "0.00",
        discountAmount: "5.00",
        status: "LOCKED",
        lockedOrderNo: "ORD-001",
        redeemedOrderNo: null,
        validFrom: "2026-07-21T00:00:00Z",
        validUntil: "2026-08-21T00:00:00Z",
        regions: [],
      },
    ])));

    const store = useBenefitsStore();
    await store.load(access());

    expect(store.benefits).toHaveLength(2);
    expect(store.availableCount).toBe(1);
    expect(store.activeOwnerId).toBe("2079000000000000999");
  });

  it("clears the previous owner and rejects its late response", async () => {
    let resolveFirst!: (response: Response) => void;
    const firstResponse = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get("Authorization");
      if (authorization === "Bearer token-a") {
        return firstResponse;
      }
      if (authorization === "Bearer token-b") {
        return success([benefit("BEN-B", "2079000000000002999")]);
      }
      throw new Error(`Unexpected authorization: ${authorization}`);
    }));

    const store = useBenefitsStore();
    const firstLoad = store.load(access());
    await vi.waitFor(() => {
      expect(store.loading).toBe(true);
    });

    await store.load(access("2079000000000002999", "token-b"));
    expect(store.benefits.map((item) => item.benefitNo)).toEqual(["BEN-B"]);

    resolveFirst(success([benefit("BEN-A", "2079000000000000999")]));
    await expect(firstLoad).rejects.toBeInstanceOf(BenefitAccessChangedError);
    expect(store.benefits.map((item) => item.benefitNo)).toEqual(["BEN-B"]);
  });

  it("refuses to display a benefit owned by another account", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success([benefit("BEN-FOREIGN", "2079000000000002999")])));

    const store = useBenefitsStore();
    await store.load(access());

    expect(store.benefits).toEqual([]);
    expect(store.error).toContain("不属于当前账户");
  });

  it("clears confirmed benefits when the session becomes unauthenticated", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success([benefit("BEN-A", "2079000000000000999")])));

    const store = useBenefitsStore();
    await store.load(access());
    await store.load({
      authenticated: false,
      ownerId: null,
      accessToken: null,
    });

    expect(store.benefits).toEqual([]);
    expect(store.activeOwnerId).toBeNull();
  });
});

function access(
  ownerId = "2079000000000000999",
  accessToken = "token-a",
): BenefitAccessContext {
  return { authenticated: true, ownerId, accessToken };
}

function benefit(benefitNo: string, userId: string) {
  return {
    benefitNo,
    userId,
    ruleCode: "COUPON-10",
    benefitType: "COUPON" as const,
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    status: "AVAILABLE",
    lockedOrderNo: null,
    redeemedOrderNo: null,
    validFrom: "2026-08-01T00:00:00Z",
    validUntil: "2026-09-01T00:00:00Z",
    regions: [],
  };
}
