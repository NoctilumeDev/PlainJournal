import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createTradeApi } from "./trade";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-20T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("trade api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps the stable order idempotency key on create and lookup", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({
        orderNo: "ORD-001",
        status: "PENDING_PAYMENT",
        totalAmount: "368.00",
        priceSnapshot: null,
        paymentDeadline: "2026-07-20T00:15:00Z",
        closeReason: null,
        deliveryAddress: {
          sourceAddressId: "2079000000000000888",
        },
        items: [],
        version: 0,
        createdAt: "2026-07-20T00:00:00Z",
        updatedAt: "2026-07-20T00:00:00Z",
      });
    }));

    const api = createTradeApi(createApiClient());
    const key = "order:00000000-0000-0000-0000-000000000001";
    await api.createOrder({
      addressId: "2079000000000000888",
      items: [{
        productId: "2079000000000000001",
        skuId: "2079000000000000011",
        quantity: 2,
      }],
      benefitNos: ["BEN-001"],
    }, key);
    await api.orderByIdempotencyKey(key);

    const createHeaders = new Headers(requests[0]?.init?.headers);
    expect(createHeaders.get("Idempotency-Key")).toBe(key);
    expect(requests[1]?.url).toContain(
      "/api/v1/trade/orders/by-idempotency-key/order%3A00000000-0000-0000-0000-000000000001",
    );
  });

  it("maps account-cart state replacement and removal to the existing Trade contract", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      if (init?.method === "DELETE") {
        return success(null);
      }
      return success({
        id: "2079000000000000777",
        productId: "2079000000000000001",
        skuId: "2079000000000000011",
        productTitle: "帆布通勤袋",
        skuName: "自然色 / 中号",
        specJson: "{}",
        unitPrice: "189.00",
        quantity: 3,
        selected: false,
      });
    }));

    const api = createTradeApi(createApiClient());
    await api.putCartItem("2079000000000000011", {
      productId: "2079000000000000001",
      quantity: 3,
      selected: false,
    });
    await api.removeCartItem("2079000000000000011");

    expect(requests[0]?.url).toContain(
      "/api/v1/trade/cart/items/2079000000000000011",
    );
    expect(requests[0]?.init?.method).toBe("PUT");
    expect(JSON.parse(String(requests[0]?.init?.body))).toEqual({
      productId: "2079000000000000001",
      quantity: 3,
      selected: false,
    });
    expect(requests[1]?.url).toContain(
      "/api/v1/trade/cart/items/2079000000000000011",
    );
    expect(requests[1]?.init?.method).toBe("DELETE");
  });

  it("lists current-user orders and posts cancellation to the encoded order path", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({ items: [], page: 2, size: 30, total: 0 });
    }));

    const api = createTradeApi(createApiClient());
    await api.orders(2, 30);
    await api.cancelOrder("ORD:2026/07");

    expect(requests[0]?.url).toContain("/api/v1/trade/orders/page?page=2&size=30");
    expect(requests[0]?.init?.method).toBeUndefined();
    expect(requests[1]?.url).toContain(
      "/api/v1/trade/orders/ORD%3A2026%2F07/cancel",
    );
    expect(requests[1]?.init?.method).toBe("POST");
  });

  it("keeps the after-sale application key and separates customer from admin paths", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success([]);
    }));

    const api = createTradeApi(createApiClient());
    const key = "after-sale:00000000-0000-0000-0000-000000000001";
    await api.applyAfterSale("ORD:2026/07", "商品存在明确问题", key);
    await api.afterSale("AS:2026/07");
    await api.adminAfterSales("APPLIED");
    await api.reviewAfterSale("AS:2026/07", {
      approved: true,
      reason: "符合整单退货条件",
    });

    expect(new Headers(requests[0]?.init?.headers).get("Idempotency-Key")).toBe(key);
    expect(requests[0]?.url).toContain("/orders/ORD%3A2026%2F07/after-sales");
    expect(requests[1]?.url).toContain("/trade/after-sales/AS%3A2026%2F07");
    expect(requests[2]?.url).toContain("/trade/admin/after-sales?status=APPLIED");
    expect(requests[3]?.url).toContain("/trade/admin/after-sales/AS%3A2026%2F07/review");
    expect(requests[3]?.init?.method).toBe("POST");
  });
});
