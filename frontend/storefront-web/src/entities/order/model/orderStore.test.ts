import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { Order } from "@plain-journal/foundation";

import {
  OrderAccessChangedError,
  useOrdersStore,
  type OrderAccessContext,
} from "./orderStore";

const USER_ID = "2079000000000000999";
const OTHER_USER_ID = "2079000000000001999";
const ORDER_NO = "ORD2079000000000000001";
const LEGACY_PENDING_KEY = "plain-journal:pending-order-cancellation:v1";
const PENDING_KEY = `plain-journal:pending-order-cancellation:v2:${USER_ID}`;
const OTHER_PENDING_KEY = `plain-journal:pending-order-cancellation:v2:${OTHER_USER_ID}`;
const ACCESS: OrderAccessContext = {
  authenticated: true,
  ownerId: USER_ID,
  accessToken: "access-token-a",
};
const OTHER_ACCESS: OrderAccessContext = {
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

function orderFixture(status = "PENDING_PAYMENT", orderNo = ORDER_NO): Order {
  return {
    orderNo,
    status,
    totalAmount: "398.00",
    priceSnapshot: {
      marketingLockNo: "PLK2079000000000000001",
      originalAmount: "398.00",
      couponDiscount: "0.00",
      redPacketDiscount: "0.00",
      subsidyDiscount: "0.00",
      discountAmount: "0.00",
      payableAmount: "398.00",
      pricingVersion: "v1",
      allocations: [],
    },
    paymentDeadline: "2026-07-21T00:15:00Z",
    closeReason: status === "CANCELED" ? "USER_CANCELED" : null,
    deliveryAddress: {
      sourceAddressId: "2079000000000000888",
      recipientName: "Test Customer",
      phone: "+86 13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "文三路 1 号",
      postalCode: "310000",
    },
    items: [{
      lineNo: 1,
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuCode: "BAG-NATURAL-M",
      skuName: "自然色 / 中号",
      specJson: "{}",
      imageObjectKey: null,
      unitPrice: "199.00",
      quantity: 2,
      lineAmount: "398.00",
      discountAmount: "0.00",
      payableAmount: "398.00",
    }],
    version: status === "PENDING_PAYMENT" ? 1 : 2,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:00:01Z",
  };
}

describe("customer orders", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads the current-user order list in the service order", async () => {
    const newer = {
      ...orderFixture("PENDING_PAYMENT", "ORD-NEW"),
      createdAt: "2026-07-21T01:00:00Z",
    };
    const older = orderFixture("CLOSED", "ORD-OLD");
    vi.stubGlobal("fetch", vi.fn(async () => success({
      items: [older, newer],
      page: 1,
      size: 20,
      total: 2,
    })));

    const orders = useOrdersStore();
    await orders.load(ACCESS);

    expect(orders.orders.map((order) => order.orderNo)).toEqual(["ORD-NEW", "ORD-OLD"]);
    expect(orders.total).toBe(2);
    expect(orders.hasMore).toBe(false);
    expect(orders.error).toBeNull();
  });

  it("loads later order pages without duplicating existing facts", async () => {
    const first = orderFixture("PENDING_PAYMENT", "ORD-001");
    const second = orderFixture("CLOSED", "ORD-002");
    const requestedPages: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), "http://local");
      requestedPages.push(url.searchParams.get("page") ?? "");
      return url.searchParams.get("page") === "1"
        ? success({ items: [first], page: 1, size: 20, total: 2 })
        : success({ items: [first, second], page: 2, size: 20, total: 2 });
    }));

    const orders = useOrdersStore();
    await orders.load(ACCESS);
    await orders.loadMore(ACCESS);

    expect(requestedPages).toEqual(["1", "2"]);
    expect(orders.orders.map((order) => order.orderNo)).toEqual(["ORD-001", "ORD-002"]);
    expect(orders.hasMore).toBe(false);
  });

  it("localizes an owner-scoped order 404 without exposing the backend message", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => failure(
      404,
      "RESOURCE_NOT_FOUND",
      "The requested trade resource does not exist",
    )));

    const orders = useOrdersStore();
    const value = await orders.loadOrder(OTHER_ACCESS, ORDER_NO);

    expect(value).toBeNull();
    expect(orders.error).toBe("订单不存在，或不属于当前账户。");
    expect(orders.error).not.toContain("trade resource");
  });

  it("accepts CANCELED only after Trade returns the terminal fact", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      expect(url.pathname).toBe(`/api/v1/trade/orders/${ORDER_NO}/cancel`);
      expect(init?.method).toBe("POST");
      return success(orderFixture("CANCELED"));
    });
    vi.stubGlobal("fetch", fetchMock);

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    const result = await orders.cancelOrder(ACCESS, ORDER_NO);

    expect(result?.status).toBe("CANCELED");
    expect(orders.order(ORDER_NO)?.status).toBe("CANCELED");
    expect(orders.cancellationUnknown).toBe(false);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("keeps CANCELING visible instead of claiming cancellation completed", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success(orderFixture("CANCELING"))));

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    const result = await orders.cancelOrder(ACCESS, ORDER_NO);

    expect(result?.status).toBe("CANCELING");
    expect(orders.order(ORDER_NO)?.status).toBe("CANCELING");
    expect(orders.cancellationUnknown).toBe(false);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("recovers a lost cancellation response by querying the Trade order fact", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      methods.push(init?.method ?? "GET");
      if (init?.method === "POST") {
        throw new Error("response lost after commit");
      }
      return success(orderFixture("CANCELED"));
    }));

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    const result = await orders.cancelOrder(ACCESS, ORDER_NO);

    expect(methods).toEqual(["POST", "GET"]);
    expect(result?.status).toBe("CANCELED");
    expect(orders.cancellationUnknown).toBe(false);
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it("retains the same pending cancellation when Trade still reports PENDING_PAYMENT", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      methods.push(init?.method ?? "GET");
      if (init?.method === "POST") {
        return failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable");
      }
      return success(orderFixture());
    }));

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    const result = await orders.cancelOrder(ACCESS, ORDER_NO);
    const stored = JSON.parse(localStorage.getItem(PENDING_KEY) ?? "null") as {
      userId: string;
      orderNo: string;
    } | null;

    expect(methods).toEqual(["POST", "GET"]);
    expect(result?.status).toBe("PENDING_PAYMENT");
    expect(orders.cancellationUnknown).toBe(true);
    expect(stored).toMatchObject({ userId: USER_ID, orderNo: ORDER_NO });
  });

  it("queries first and then safely retries the same cancellation path", async () => {
    let postAttempts = 0;
    const requests: Array<{ path: string; method: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "http://local");
      const method = init?.method ?? "GET";
      requests.push({ path: url.pathname, method });
      if (method === "POST") {
        postAttempts += 1;
        if (postAttempts === 1) {
          throw new Error("response lost");
        }
        return success(orderFixture("CANCELED"));
      }
      return success(orderFixture());
    }));

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    await orders.cancelOrder(ACCESS, ORDER_NO);
    const retried = await orders.cancelOrder(ACCESS, ORDER_NO);

    expect(retried?.status).toBe("CANCELED");
    expect(requests.map((request) => request.method)).toEqual([
      "POST",
      "GET",
      "GET",
      "POST",
    ]);
    expect(requests.filter((request) => request.method === "POST").map((request) => request.path))
      .toEqual([
        `/api/v1/trade/orders/${ORDER_NO}/cancel`,
        `/api/v1/trade/orders/${ORDER_NO}/cancel`,
      ]);
  });

  it("keeps pending cancellation scoped to its owner without blocking another account", async () => {
    localStorage.setItem(PENDING_KEY, JSON.stringify({
      userId: USER_ID,
      orderNo: ORDER_NO,
      createdAt: "2026-07-21T00:00:00Z",
    }));
    const fetchMock = vi.fn(async () => success(orderFixture("CANCELED")));
    vi.stubGlobal("fetch", fetchMock);

    const orders = useOrdersStore();
    orders.synchronizeAccess(OTHER_ACCESS);
    orders.orders.push(orderFixture());
    const result = await orders.cancelOrder(OTHER_ACCESS, ORDER_NO);

    expect(result?.status).toBe("CANCELED");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    expect(localStorage.getItem(OTHER_PENDING_KEY)).toBeNull();
  });

  it("migrates only the current owner's legacy pending cancellation", () => {
    localStorage.setItem(LEGACY_PENDING_KEY, JSON.stringify({
      userId: USER_ID,
      orderNo: ORDER_NO,
      createdAt: "2026-07-21T00:00:00Z",
    }));

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);

    expect(orders.currentAccountPendingCancellation?.orderNo).toBe(ORDER_NO);
    expect(localStorage.getItem(PENDING_KEY)).not.toBeNull();
    expect(localStorage.getItem(LEGACY_PENDING_KEY)).toBeNull();
  });

  it("rejects account A's late list response after switching to B", async () => {
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
        items: [orderFixture("PENDING_PAYMENT", "ORD-B")],
        page: 1,
        size: 20,
        total: 1,
      });
    }));

    const orders = useOrdersStore();
    const loadA = orders.load(ACCESS);
    await orders.load(OTHER_ACCESS);
    resolveA(success({
      items: [orderFixture("PENDING_PAYMENT", "ORD-A")],
      page: 1,
      size: 20,
      total: 1,
    }));

    await expect(loadA).rejects.toBeInstanceOf(OrderAccessChangedError);
    expect(orders.orders.map((value) => value.orderNo)).toEqual(["ORD-B"]);
  });

  it("coalesces concurrent cancellation clicks into one Trade POST", async () => {
    let resolveCancel!: (response: Response) => void;
    const delayedCancel = new Promise<Response>((resolve) => {
      resolveCancel = resolve;
    });
    const fetchMock = vi.fn(async () => delayedCancel);
    vi.stubGlobal("fetch", fetchMock);

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture());
    const first = orders.cancelOrder(ACCESS, ORDER_NO);
    const second = orders.cancelOrder(ACCESS, ORDER_NO);
    resolveCancel(success(orderFixture("CANCELED")));

    await expect(Promise.all([first, second])).resolves.toEqual([
      expect.objectContaining({ status: "CANCELED" }),
      expect.objectContaining({ status: "CANCELED" }),
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not post cancellation for a non-cancelable order fact", async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error("non-cancelable order must not send a request");
    });
    vi.stubGlobal("fetch", fetchMock);

    const orders = useOrdersStore();
    orders.synchronizeAccess(ACCESS);
    orders.orders.push(orderFixture("CANCELED"));
    const result = await orders.cancelOrder(ACCESS, ORDER_NO);

    expect(result?.status).toBe("CANCELED");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
