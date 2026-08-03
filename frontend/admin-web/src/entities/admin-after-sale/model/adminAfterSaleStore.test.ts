import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { AfterSale } from "@plain-journal/foundation";

import {
  useAdminAfterSaleStore,
  type AdminAfterSaleAccessContext,
} from "./adminAfterSaleStore";

const OPERATOR_ID = "2088000000000000001";
const ACCESS: AdminAfterSaleAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "admin-token",
};
const UPDATED_ACCESS: AdminAfterSaleAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "admin-token-refreshed",
};
const OTHER_ACCESS: AdminAfterSaleAccessContext = {
  authorized: true,
  operatorId: "2088000000000000002",
  accessToken: "other-admin-token",
};
const AFTER_SALE_NO = "AS2088000000000000101";
const STORAGE_KEY =
  `plain-journal:admin-after-sale:pending-review:v1:${OPERATOR_ID}`;

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

function afterSaleFixture(
  overrides: Partial<AfterSale> = {},
): AfterSale {
  return {
    afterSaleNo: AFTER_SALE_NO,
    orderNo: "ORD2088000000000000102",
    userId: "2088000000000000103",
    afterSaleType: "WHOLE_RETURN_REFUND",
    status: "APPLIED",
    reason: "整单商品存在明确破损",
    reviewReason: null,
    refundAmount: "378.00",
    returnReceiptNo: null,
    refundNo: null,
    items: [{
      lineNo: 1,
      skuId: "2088000000000000104",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      quantity: 2,
      lineAmount: "398.00",
      discountAmount: "20.00",
      refundableAmount: "378.00",
    }],
    version: 0,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:00:00Z",
    approvedAt: null,
    completedAt: null,
    ...overrides,
  };
}

function fillApproval(
  store: ReturnType<typeof useAdminAfterSaleStore>,
  reason = "核对订单快照后同意整单退货退款",
) {
  const form = store.reviewForm(AFTER_SALE_NO);
  form.approved = true;
  form.reason = reason;
}

describe("admin after-sale entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads a filtered Trade projection with string identities and item snapshots", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
    ) => {
      const url = new URL(String(input), "http://localhost");
      requests.push(`${url.pathname}${url.search}`);
      return success([
        afterSaleFixture({
          status: "REFUNDING",
          reviewReason: "审核通过",
          approvedAt: "2026-08-03T00:10:00Z",
        }),
      ]);
    }));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    store.status = "REFUNDING";
    await store.loadFacts(ACCESS);

    expect(requests).toEqual([
      "/api/v1/trade/admin/after-sales?status=REFUNDING",
    ]);
    expect(store.afterSales[0]?.userId).toBe("2088000000000000103");
    expect(store.afterSales[0]?.items[0]?.skuId)
      .toBe("2088000000000000104");
    expect(store.afterSales[0]?.refundAmount).toBe("378.00");
    expect(store.loadError).toBeNull();
  });

  it("confirms a committed lost approval only through matching Trade authority", async () => {
    const reason = "权威读取确认原审核原因";
    let postAttempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      if (init?.method === "POST") {
        postAttempts += 1;
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      if (url.pathname.endsWith(`/${AFTER_SALE_NO}`)) {
        return success(afterSaleFixture({
          status: "WAIT_RETURN",
          reviewReason: reason,
          approvedAt: "2026-08-03T00:10:00Z",
          version: 1,
        }));
      }
      return success([afterSaleFixture()]);
    }));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    fillApproval(store, reason);
    await store.review(AFTER_SALE_NO, ACCESS);

    expect(store.reviewPhase).toBe("unknown");
    expect(store.pendingReferenceNo).toBe(AFTER_SALE_NO);
    expect(store.canRetryPending).toBe(false);

    await store.readPendingAuthority(ACCESS);

    expect(postAttempts).toBe(1);
    expect(store.reviewPhase).toBe("accepted");
    expect(store.reviewMessage).toContain("不伪造命令身份");
    expect(store.pendingReview).toBeNull();
    expect(store.afterSales[0]?.status).toBe("WAIT_RETURN");
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("retries the exact frozen payload only after authority still reports APPLIED", async () => {
    const reason = "响应未提交时沿用原审核载荷";
    const posted: Array<Record<string, unknown>> = [];
    let postAttempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      if (init?.method === "POST") {
        postAttempts += 1;
        posted.push(
          JSON.parse(String(init.body)) as Record<string, unknown>,
        );
        return postAttempts === 1
          ? failure(503, "SERVICE_UNAVAILABLE", "response lost")
          : success(afterSaleFixture({
              status: "WAIT_RETURN",
              reviewReason: reason,
              approvedAt: "2026-08-03T00:10:00Z",
              version: 1,
            }));
      }
      if (url.pathname.endsWith(`/${AFTER_SALE_NO}`)) {
        return success(afterSaleFixture());
      }
      return success([afterSaleFixture()]);
    }));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    fillApproval(store, reason);
    await store.review(AFTER_SALE_NO, ACCESS);

    await store.retryPending(ACCESS);
    expect(postAttempts).toBe(1);
    expect(store.reviewMessage).toContain("必须先读取");

    await store.readPendingAuthority(ACCESS);
    expect(store.canRetryPending).toBe(true);
    await store.retryPending(ACCESS);

    expect(posted).toEqual([
      { approved: true, reason },
      { approved: true, reason },
    ]);
    expect(store.reviewPhase).toBe("accepted");
    expect(store.pendingReview).toBeNull();
  });

  it("rejects authority from another review decision instead of attributing it to the pending command", async () => {
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://localhost");
      if (init?.method === "POST") {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      if (url.pathname.endsWith(`/${AFTER_SALE_NO}`)) {
        return success(afterSaleFixture({
          status: "REJECTED",
          reviewReason: "另一位管理员拒绝了申请",
          version: 1,
        }));
      }
      return success([afterSaleFixture()]);
    }));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    fillApproval(store);
    await store.review(AFTER_SALE_NO, ACCESS);
    await store.readPendingAuthority(ACCESS);

    expect(store.reviewPhase).toBe("rejected");
    expect(store.reviewMessage).toContain("不能证明原载荷");
    expect(store.pendingReview).toBeNull();
    expect(store.afterSales[0]?.status).toBe("REJECTED");
  });

  it("keeps a mismatched direct response unknown and preserves the pending review", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success(afterSaleFixture({
        status: "WAIT_RETURN",
        reviewReason: "返回了其他审核原因",
        approvedAt: "2026-08-03T00:10:00Z",
        version: 1,
      }))));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    fillApproval(store);
    await store.review(AFTER_SALE_NO, ACCESS);

    expect(store.reviewPhase).toBe("unknown");
    expect(store.reviewMessage).toContain("原审核载荷不一致");
    expect(store.pendingReview?.referenceNo).toBe(AFTER_SALE_NO);
    expect(store.afterSales).toEqual([]);
  });

  it("restores pending payload per operator and isolates it after an account switch", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const first = useAdminAfterSaleStore();
    first.synchronizeAccess(ACCESS);
    fillApproval(first, "保存到当前管理员名下");
    await first.review(AFTER_SALE_NO, ACCESS);

    setActivePinia(createPinia());
    const restored = useAdminAfterSaleStore();
    restored.synchronizeAccess(ACCESS);
    expect(restored.reviewPhase).toBe("unknown");
    expect(restored.reviewForm(AFTER_SALE_NO)).toEqual({
      approved: true,
      reason: "保存到当前管理员名下",
    });

    restored.synchronizeAccess(OTHER_ACCESS);
    expect(restored.pendingReview).toBeNull();
    expect(restored.reviewPhase).toBe("idle");
    expect(restored.afterSales).toEqual([]);
    expect(localStorage.getItem(STORAGE_KEY)).toContain(AFTER_SALE_NO);
  });

  it("does not write a completed review after the access token changes", async () => {
    let resolveReview!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveReview = resolve;
      })));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    fillApproval(store, "旧 token 的审核响应");
    const request = store.review(AFTER_SALE_NO, ACCESS);
    store.synchronizeAccess(UPDATED_ACCESS);
    resolveReview(success(afterSaleFixture({
      status: "WAIT_RETURN",
      reviewReason: "旧 token 的审核响应",
      approvedAt: "2026-08-03T00:10:00Z",
      version: 1,
    })));
    await request;

    expect(store.afterSales).toEqual([]);
    expect(store.reviewPhase).toBe("unknown");
    expect(store.reviewMessage).toContain("会话凭据已更新");
    expect(store.pendingReview?.referenceNo).toBe(AFTER_SALE_NO);
  });

  it("preserves known facts when a list refresh returns 503", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      attempts += 1;
      return attempts === 1
        ? success([afterSaleFixture()])
        : failure(
            503,
            "SERVICE_UNAVAILABLE",
            "trade projection unavailable",
          );
    }));

    const store = useAdminAfterSaleStore();
    store.synchronizeAccess(ACCESS);
    await store.loadFacts(ACCESS);
    const refreshedAt = store.refreshedAt;
    await store.loadFacts(ACCESS);

    expect(store.afterSales).toEqual([afterSaleFixture()]);
    expect(store.refreshedAt).toBe(refreshedAt);
    expect(store.loadError).toBe("trade projection unavailable");
  });
});
