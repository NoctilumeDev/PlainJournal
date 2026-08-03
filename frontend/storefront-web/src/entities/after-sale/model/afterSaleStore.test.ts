import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { AfterSale } from "@plain-journal/foundation";

import { useAfterSalesStore, type AfterSaleAccessContext } from "./afterSaleStore";

const USER_ID = "2079000000000000999";
const OTHER_USER_ID = "2079000000000001999";
const ORDER_NO = "ORD2079000000000000001";
const AFTER_SALE_NO = "AS2079000000000000002";
const ACCESS: AfterSaleAccessContext = {
  authenticated: true,
  ownerId: USER_ID,
  accessToken: "access-token-a",
};
const OTHER_ACCESS: AfterSaleAccessContext = {
  authenticated: true,
  ownerId: OTHER_USER_ID,
  accessToken: "access-token-b",
};
const PENDING_KEY = `plain-journal:pending-after-sale:v2:${USER_ID}`;

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

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function fixture(
  status = "APPLIED",
  userId = USER_ID,
  orderNo = ORDER_NO,
  afterSaleNo = AFTER_SALE_NO,
): AfterSale {
  return {
    afterSaleNo,
    orderNo,
    userId,
    afterSaleType: "RETURN_REFUND",
    status,
    reason: "商品存在明确问题",
    reviewReason: null,
    refundAmount: "398.00",
    returnReceiptNo: null,
    refundNo: null,
    items: [],
    version: 0,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:00:00Z",
    approvedAt: null,
    completedAt: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe("customer after-sale entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("coalesces duplicate applications and uses one account-scoped idempotency key", async () => {
    const keys: Array<string | null> = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get("Idempotency-Key"));
      return success(fixture());
    }));

    const store = useAfterSalesStore();
    const first = store.apply(ACCESS, ORDER_NO, "商品存在明确问题");
    const second = store.apply(ACCESS, ORDER_NO, "不会产生第二次提交");
    const [firstValue, secondValue] = await Promise.all([first, second]);

    expect(firstValue?.afterSaleNo).toBe(AFTER_SALE_NO);
    expect(secondValue?.afterSaleNo).toBe(AFTER_SALE_NO);
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^after-sale:/);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("recovers a lost application response from the current account list", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      methods.push(method);
      return method === "POST"
        ? failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable")
        : success([fixture()]);
    }));

    const store = useAfterSalesStore();
    const value = await store.apply(ACCESS, ORDER_NO, "商品存在明确问题");

    expect(methods).toEqual(["POST", "GET"]);
    expect(value?.afterSaleNo).toBe(AFTER_SALE_NO);
    expect(store.applicationUnknown).toBe(false);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("keeps the original key when neither submission nor query confirms a fact", async () => {
    const keys: Array<string | null> = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        keys.push(new Headers(init.headers).get("Idempotency-Key"));
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      return success([]);
    }));

    const store = useAfterSalesStore();
    await store.apply(ACCESS, ORDER_NO, "商品存在明确问题");
    await store.apply(ACCESS, ORDER_NO, "不会替换原原因");

    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    expect(store.applicationUnknown).toBe(true);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
  });

  it("drops a late list response after the owner changes", async () => {
    const oldResponse = deferred<Response>();
    let request = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      request += 1;
      return request === 1
        ? oldResponse.promise
        : success([fixture("APPLIED", OTHER_USER_ID, "ORD-B", "AS-B")]);
    }));

    const store = useAfterSalesStore();
    const oldLoad = store.load(ACCESS);
    const newLoad = store.load(OTHER_ACCESS);
    await newLoad;
    oldResponse.resolve(success([fixture()]));
    await oldLoad;

    expect(store.activeOwnerId).toBe(OTHER_USER_ID);
    expect(store.afterSales.map((value) => value.afterSaleNo)).toEqual(["AS-B"]);
  });

  it("rejects a response that claims another owner", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([
      fixture("APPLIED", OTHER_USER_ID, "ORD-B", "AS-B"),
    ])));

    const store = useAfterSalesStore();
    await store.load(ACCESS);

    expect(store.afterSales).toEqual([]);
    expect(store.error).toContain("账户或请求不一致");
  });

  it("accepts cancellation recovery only after Trade returns CANCELED", async () => {
    let reads = 0;
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      reads += 1;
      return success(fixture(reads === 1 ? "APPLIED" : "CANCELED"));
    }));

    const store = useAfterSalesStore();
    const value = await store.cancel(ACCESS, AFTER_SALE_NO);

    expect(reads).toBe(2);
    expect(value?.status).toBe("CANCELED");
    expect(store.cancellationUnknown).toBe(false);
  });
});
