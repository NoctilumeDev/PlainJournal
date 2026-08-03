import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { Refund } from "@plain-journal/foundation";

import { useRefundsStore, type RefundAccessContext } from "./refundStore";

const USER_ID = "2079000000000000999";
const OTHER_USER_ID = "2079000000000001999";
const ACCESS: RefundAccessContext = {
  authenticated: true,
  ownerId: USER_ID,
  accessToken: "access-token-a",
};
const OTHER_ACCESS: RefundAccessContext = {
  authenticated: true,
  ownerId: OTHER_USER_ID,
  accessToken: "access-token-b",
};

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-01T00:00:00Z",
  }), { status: 200, headers: { "Content-Type": "application/json" } });
}

function fixture(userId = USER_ID, afterSaleNo = "AS-A", refundNo = "REF-A"): Refund {
  return {
    refundNo,
    afterSaleNo,
    orderNo: userId === USER_ID ? "ORD-A" : "ORD-B",
    paymentNo: userId === USER_ID ? "PAY-A" : "PAY-B",
    userId,
    channel: "MOCK",
    status: "PROCESSING",
    amount: "398.00",
    channelRefundNo: null,
    requestStatus: "PENDING",
    requestAttempts: 0,
    nextRequestAt: null,
    requestSentAt: null,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    refundedAt: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe("customer refund entity", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("stores only a matching Payment refund fact", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success(fixture())));
    const store = useRefundsStore();
    const value = await store.loadByAfterSale(ACCESS, "AS-A");
    expect(value?.refundNo).toBe("REF-A");
    expect(store.forAfterSale("AS-A")?.userId).toBe(USER_ID);
  });

  it("rejects a refund returned for another account", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success(
      fixture(OTHER_USER_ID, "AS-A", "REF-B"),
    )));
    const store = useRefundsStore();
    await store.loadByAfterSale(ACCESS, "AS-A", false);
    expect(store.refunds).toEqual([]);
    expect(store.error).toContain("账户或售后记录不一致");
  });

  it("drops a late refund response after an account switch", async () => {
    const oldResponse = deferred<Response>();
    let request = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      request += 1;
      return request === 1
        ? oldResponse.promise
        : success(fixture(OTHER_USER_ID, "AS-B", "REF-B"));
    }));
    const store = useRefundsStore();
    const oldLoad = store.loadByAfterSale(ACCESS, "AS-A");
    await store.loadByAfterSale(OTHER_ACCESS, "AS-B");
    oldResponse.resolve(success(fixture()));
    await oldLoad;
    expect(store.activeOwnerId).toBe(OTHER_USER_ID);
    expect(store.refunds.map((value) => value.refundNo)).toEqual(["REF-B"]);
  });
});
