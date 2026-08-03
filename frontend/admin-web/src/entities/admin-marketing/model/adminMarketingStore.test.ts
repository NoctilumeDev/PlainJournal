import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  Benefit,
  CreateMarketingRuleInput,
  MarketingRule,
} from "@plain-journal/foundation";

import {
  useAdminMarketingStore,
  type AdminMarketingAccessContext,
} from "./adminMarketingStore";

const OPERATOR_ID = "2086000000000000001";
const ACCESS: AdminMarketingAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "operator-token",
};
const UPDATED_ACCESS: AdminMarketingAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "operator-token-refreshed",
};
const OTHER_ACCESS: AdminMarketingAccessContext = {
  authorized: true,
  operatorId: "2086000000000000002",
  accessToken: "admin-token",
};
const USER_ID = "2086000000000000003";
const STORAGE_KEY =
  `plain-journal:admin-marketing:pending-command:v1:${OPERATOR_ID}`;

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

function ruleFixture(
  overrides: Partial<MarketingRule> = {},
): MarketingRule {
  return {
    ruleCode: "V643-COUPON",
    name: "V6.4.3 营销券",
    benefitType: "COUPON",
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    stackOrder: 10,
    validFrom: "2026-08-03T00:00:00.000Z",
    validUntil: "2026-09-03T00:00:00.000Z",
    status: "ACTIVE",
    regions: [{
      level: "DISTRICT",
      regionCode: "330106",
    }],
    version: 0,
    ...overrides,
  };
}

function benefitFixture(
  overrides: Partial<Benefit> = {},
): Benefit {
  return {
    benefitNo: "BEN2086000000000000004",
    userId: USER_ID,
    ruleCode: "V643-COUPON",
    benefitType: "COUPON",
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    status: "AVAILABLE",
    lockedOrderNo: null,
    redeemedOrderNo: null,
    validFrom: "2026-08-03T00:00:00.000Z",
    validUntil: "2026-09-03T00:00:00.000Z",
    regions: [],
    ...overrides,
  };
}

function fillRule(store: ReturnType<typeof useAdminMarketingStore>) {
  Object.assign(store.rule, {
    ruleCode: "V643-COUPON",
    name: "V6.4.3 营销券",
    benefitType: "COUPON",
    thresholdAmount: "100.00",
    discountAmount: "10.00",
    stackOrder: 10,
    validFrom: "2026-08-03T08:00",
    validUntil: "2026-09-03T08:00",
    regionLevel: "DISTRICT",
    regionCode: "330106",
  });
}

function fillGrant(store: ReturnType<typeof useAdminMarketingStore>) {
  store.grant.userId = USER_ID;
  store.grant.ruleCode = "V643-COUPON";
}

describe("admin marketing entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps a lost grant unknown and retries the exact user, rule and grant key", async () => {
    const posted: Array<Record<string, unknown>> = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      posted.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      if (attempts === 1) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success(benefitFixture());
    }));

    const store = useAdminMarketingStore();
    store.synchronizeAccess(ACCESS);
    fillGrant(store);
    const grantKey = store.grant.grantKey;

    await store.grantBenefit(ACCESS);

    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCommand?.commandKey).toBe(grantKey);
    expect(localStorage.getItem(STORAGE_KEY)).toContain(grantKey);

    await store.retryPending(ACCESS);

    expect(posted).toEqual([
      { userId: USER_ID, ruleCode: "V643-COUPON", grantKey },
      { userId: USER_ID, ruleCode: "V643-COUPON", grantKey },
    ]);
    expect(store.commandPhase).toBe("accepted");
    expect(store.grantedBenefit).toEqual(benefitFixture());
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(store.grant.grantKey).not.toBe(grantKey);
  });

  it("restores an unresolved grant with its original identity and payload", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const first = useAdminMarketingStore();
    first.synchronizeAccess(ACCESS);
    fillGrant(first);
    const grantKey = first.grant.grantKey;
    await first.grantBenefit(ACCESS);

    setActivePinia(createPinia());
    const restored = useAdminMarketingStore();
    restored.synchronizeAccess(ACCESS);

    expect(restored.commandPhase).toBe("unknown");
    expect(restored.pendingCommand?.kind).toBe("grant");
    expect(restored.grant).toEqual({
      userId: USER_ID,
      ruleCode: "V643-COUPON",
      grantKey,
    });
  });

  it("treats an explicit grant conflict as rejected and clears the pending identity", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(
        409,
        "IDEMPOTENCY_CONFLICT",
        "grant key belongs to another rule",
      )));

    const store = useAdminMarketingStore();
    store.synchronizeAccess(ACCESS);
    fillGrant(store);
    await store.grantBenefit(ACCESS);

    expect(store.commandPhase).toBe("rejected");
    expect(store.commandMessage).toBe("grant key belongs to another rule");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("keeps a lost rule creation unknown and never repeats a POST without authority", async () => {
    const posted: CreateMarketingRuleInput[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      posted.push(
        JSON.parse(String(init?.body)) as CreateMarketingRuleInput,
      );
      return failure(503, "SERVICE_UNAVAILABLE", "response lost");
    }));

    const store = useAdminMarketingStore();
    store.synchronizeAccess(ACCESS);
    fillRule(store);
    await store.createRule(ACCESS);

    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("没有管理端规则查询");
    expect(store.pendingCommand?.kind).toBe("rule");
    expect(store.createdRule).toBeNull();

    await store.retryPending(ACCESS);

    expect(posted).toHaveLength(1);
    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("不能盲目重复 POST");
    expect(localStorage.getItem(STORAGE_KEY)).toContain("V643-COUPON");
  });

  it("keeps a mismatched Marketing response unknown instead of publishing false facts", async () => {
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
    ) => {
      const path = new URL(String(input), "http://localhost").pathname;
      return path.endsWith("/admin/rules")
        ? success(ruleFixture({ discountAmount: "99.00" }))
        : success(benefitFixture({ userId: "2086000000000000999" }));
    }));

    const ruleStore = useAdminMarketingStore();
    ruleStore.synchronizeAccess(ACCESS);
    fillRule(ruleStore);
    await ruleStore.createRule(ACCESS);
    expect(ruleStore.commandPhase).toBe("unknown");
    expect(ruleStore.createdRule).toBeNull();
    expect(ruleStore.commandMessage).toContain("完整载荷不一致");

    localStorage.clear();
    setActivePinia(createPinia());
    const grantStore = useAdminMarketingStore();
    grantStore.synchronizeAccess(ACCESS);
    fillGrant(grantStore);
    await grantStore.grantBenefit(ACCESS);
    expect(grantStore.commandPhase).toBe("unknown");
    expect(grantStore.grantedBenefit).toBeNull();
    expect(grantStore.commandMessage).toContain("当前顾客或规则不一致");
  });

  it("does not write a completed rule response after the access token changes", async () => {
    let resolveRule!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveRule = resolve;
      })));

    const store = useAdminMarketingStore();
    store.synchronizeAccess(ACCESS);
    fillRule(store);
    const request = store.createRule(ACCESS);
    store.synchronizeAccess(UPDATED_ACCESS);
    resolveRule(success(ruleFixture()));

    await expect(request).resolves.toBeNull();
    expect(store.createdRule).toBeNull();
    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("会话凭据已更新");
  });

  it("isolates pending commands and returned facts when the operator changes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const store = useAdminMarketingStore();
    store.synchronizeAccess(ACCESS);
    fillGrant(store);
    await store.grantBenefit(ACCESS);
    const originalGrantKey = store.grant.grantKey;

    store.synchronizeAccess(OTHER_ACCESS);

    expect(store.commandPhase).toBe("idle");
    expect(store.pendingCommand).toBeNull();
    expect(store.createdRule).toBeNull();
    expect(store.grantedBenefit).toBeNull();
    expect(store.grant.userId).toBe("");
    expect(store.grant.ruleCode).toBe("");
    expect(store.grant.grantKey).not.toBe(originalGrantKey);
    expect(localStorage.getItem(STORAGE_KEY)).toContain(originalGrantKey);
  });
});
