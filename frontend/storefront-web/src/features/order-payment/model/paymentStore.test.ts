import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { Payment } from "@plain-journal/foundation";

import {
  PaymentAccessChangedError,
  usePaymentsStore,
  type PaymentAccessContext,
} from "./paymentStore";

const USER_ID = "2079000000000000999";
const OTHER_USER_ID = "2079000000000001999";
const ORDER_NO = "ORD2079000000000000001";
const OTHER_ORDER_NO = "ORD2079000000000000003";
const PAYMENT_NO = "PAY2079000000000000002";
const LEGACY_PENDING_KEY = "plain-journal:pending-payment:v1";
const PENDING_KEY = `plain-journal:pending-payment:v2:${USER_ID}`;
const OTHER_PENDING_KEY = `plain-journal:pending-payment:v2:${OTHER_USER_ID}`;
const ACCESS: PaymentAccessContext = {
  authenticated: true,
  ownerId: USER_ID,
  accessToken: "access-token-a",
};
const OTHER_ACCESS: PaymentAccessContext = {
  authenticated: true,
  ownerId: OTHER_USER_ID,
  accessToken: "access-token-b",
};

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

function paymentFixture(status = "PROCESSING"): Payment {
  return {
    paymentNo: PAYMENT_NO,
    orderNo: ORDER_NO,
    channel: "MOCK",
    status,
    amount: "398.00",
    channelTransactionNo: status === "PROCESSING" ? null : "MOCK-TXN-001",
    paidAt: status === "SUCCESS" ? "2026-07-21T00:01:00Z" : null,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:01:00Z",
  };
}

describe("customer payments", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads the existing payment by order", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      expect(new URL(String(input), "http://local").pathname)
        .toBe(`/api/v1/payment/payments/by-order/${ORDER_NO}`);
      return success(paymentFixture());
    }));

    const payments = usePaymentsStore();
    const value = await payments.loadForOrder(ACCESS, ORDER_NO);

    expect(value?.status).toBe("PROCESSING");
    expect(payments.paymentForOrder(ORDER_NO)?.paymentNo).toBe(PAYMENT_NO);
  });

  it("clears the same order's device pending record after reading Payment authority", async () => {
    localStorage.setItem(PENDING_KEY, JSON.stringify({
      key: "payment:00000000-0000-0000-0000-000000000099",
      userId: USER_ID,
      orderNo: ORDER_NO,
      channel: "MOCK",
      createdAt: "2026-07-21T00:00:00Z",
    }));
    vi.stubGlobal("fetch", vi.fn(async () => success(paymentFixture())));

    const payments = usePaymentsStore();
    payments.synchronizeAccess(ACCESS);
    expect(payments.submissionUnknown).toBe(true);

    const value = await payments.loadForOrder(ACCESS, ORDER_NO);

    expect(value?.status).toBe("PROCESSING");
    expect(payments.currentAccountPendingSubmission).toBeNull();
    expect(payments.submissionUnknown).toBe(false);
    expect(payments.submissionError).toBeNull();
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("does not clear another order's unresolved device pending record", async () => {
    localStorage.setItem(PENDING_KEY, JSON.stringify({
      key: "payment:00000000-0000-0000-0000-000000000099",
      userId: USER_ID,
      orderNo: ORDER_NO,
      channel: "MOCK",
      createdAt: "2026-07-21T00:00:00Z",
    }));
    vi.stubGlobal("fetch", vi.fn(async () => success({
      ...paymentFixture(),
      paymentNo: "PAY2079000000000000004",
      orderNo: OTHER_ORDER_NO,
    })));

    const payments = usePaymentsStore();
    payments.synchronizeAccess(ACCESS);
    const value = await payments.loadForOrder(ACCESS, OTHER_ORDER_NO);

    expect(value?.orderNo).toBe(OTHER_ORDER_NO);
    expect(payments.currentAccountPendingSubmission?.orderNo).toBe(ORDER_NO);
    expect(payments.submissionUnknown).toBe(true);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
  });

  it("creates one PROCESSING payment with a stable local key", async () => {
    const requests: Array<{ path: string; method: string; key: string | null }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      requests.push({
        path: url.pathname,
        method,
        key: new Headers(init?.headers).get("Idempotency-Key"),
      });
      if (url.pathname.includes("/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      return success(paymentFixture());
    }));

    const payments = usePaymentsStore();
    const value = await payments.createForOrder(ACCESS, ORDER_NO);

    expect(value?.status).toBe("PROCESSING");
    expect(requests.map((request) => request.method)).toEqual(["GET", "POST"]);
    expect(requests[1]?.key).toMatch(/^payment:/);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("recovers a lost create response by querying the original payment key", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      methods.push(method);
      if (url.pathname.includes("/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (method === "POST") {
        throw new Error("response lost after commit");
      }
      return success(paymentFixture());
    }));

    const payments = usePaymentsStore();
    const value = await payments.createForOrder(ACCESS, ORDER_NO);

    expect(methods).toEqual(["GET", "POST", "GET"]);
    expect(value?.paymentNo).toBe(PAYMENT_NO);
    expect(payments.submissionUnknown).toBe(false);
    expect(payments.creatingOrderNo).toBeNull();
    expect(payments.resolvingSubmission).toBe(false);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("retains the original key when create and lookup do not confirm a payment", async () => {
    const postedKeys: Array<string | null> = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        postedKeys.push(new Headers(init.headers).get("Idempotency-Key"));
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
    }));

    const payments = usePaymentsStore();
    const first = await payments.createForOrder(ACCESS, ORDER_NO);
    expect(payments.creatingOrderNo).toBeNull();
    expect(payments.resolvingSubmission).toBe(false);
    const storedAfterFirst = JSON.parse(localStorage.getItem(PENDING_KEY) ?? "null") as {
      key: string;
      orderNo: string;
    } | null;
    const second = await payments.createForOrder(ACCESS, ORDER_NO);

    expect(first).toBeNull();
    expect(second).toBeNull();
    expect(payments.creatingOrderNo).toBeNull();
    expect(payments.resolvingSubmission).toBe(false);
    expect(payments.submissionUnknown).toBe(true);
    expect(storedAfterFirst?.orderNo).toBe(ORDER_NO);
    expect(postedKeys).toHaveLength(2);
    expect(postedKeys[1]).toBe(postedKeys[0]);
  });

  it("keeps pending payment scoped to its owner without blocking another account", async () => {
    localStorage.setItem(PENDING_KEY, JSON.stringify({
      key: "payment:00000000-0000-0000-0000-000000000099",
      userId: USER_ID,
      orderNo: ORDER_NO,
      channel: "MOCK",
      createdAt: "2026-07-21T00:00:00Z",
    }));
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.includes("/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      expect(init?.method).toBe("POST");
      return success(paymentFixture());
    });
    vi.stubGlobal("fetch", fetchMock);

    const payments = usePaymentsStore();
    const value = await payments.createForOrder(OTHER_ACCESS, ORDER_NO);

    expect(value?.status).toBe("PROCESSING");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    expect(localStorage.getItem(OTHER_PENDING_KEY)).toBeNull();
  });

  it("migrates only the current owner's legacy pending payment", () => {
    localStorage.setItem(LEGACY_PENDING_KEY, JSON.stringify({
      key: "payment:00000000-0000-0000-0000-000000000099",
      userId: USER_ID,
      orderNo: ORDER_NO,
      channel: "MOCK",
      createdAt: "2026-07-21T00:00:00Z",
    }));

    const payments = usePaymentsStore();
    payments.synchronizeAccess(ACCESS);

    expect(payments.currentAccountPendingSubmission?.orderNo).toBe(ORDER_NO);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    expect(localStorage.getItem(LEGACY_PENDING_KEY)).toBeNull();
  });

  it("rejects account A's late payment response after switching to B", async () => {
    let resolveA!: (response: Response) => void;
    const delayedA = new Promise<Response>((resolve) => {
      resolveA = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get("Authorization");
      if (authorization === "Bearer access-token-a") {
        return delayedA;
      }
      return success({
        ...paymentFixture(),
        paymentNo: "PAY-B",
      });
    }));

    const payments = usePaymentsStore();
    const loadA = payments.loadForOrder(ACCESS, ORDER_NO);
    await payments.loadForOrder(OTHER_ACCESS, ORDER_NO);
    resolveA(success({
      ...paymentFixture(),
      paymentNo: "PAY-A",
    }));

    await expect(loadA).rejects.toBeInstanceOf(PaymentAccessChangedError);
    expect(payments.payments.map((value) => value.paymentNo)).toEqual(["PAY-B"]);
  });

  it("coalesces concurrent create clicks into one Payment POST", async () => {
    let resolveCreate!: (response: Response) => void;
    const delayedCreate = new Promise<Response>((resolve) => {
      resolveCreate = resolve;
    });
    let postCount = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      if (url.pathname.includes("/by-order/")) {
        return failure(404, "RESOURCE_NOT_FOUND", "payment not found");
      }
      if (init?.method === "POST") {
        postCount += 1;
        return delayedCreate;
      }
      throw new Error(`unexpected request ${url.pathname}`);
    }));

    const payments = usePaymentsStore();
    const first = payments.createForOrder(ACCESS, ORDER_NO);
    const second = payments.createForOrder(ACCESS, ORDER_NO);
    await Promise.resolve();
    resolveCreate(success(paymentFixture()));

    await expect(Promise.all([first, second])).resolves.toEqual([
      expect.objectContaining({ paymentNo: PAYMENT_NO }),
      expect.objectContaining({ paymentNo: PAYMENT_NO }),
    ]);
    expect(postCount).toBe(1);
  });

  it("refreshes a known payment without creating another payment", async () => {
    const paths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      paths.push(new URL(String(input), "http://local").pathname);
      return success(paymentFixture("SUCCESS"));
    }));

    const payments = usePaymentsStore();
    payments.synchronizeAccess(ACCESS);
    payments.payments.push(paymentFixture());
    const value = await payments.refreshPayment(ACCESS, PAYMENT_NO);

    expect(value?.status).toBe("SUCCESS");
    expect(paths).toEqual([`/api/v1/payment/payments/${PAYMENT_NO}`]);
  });
});
