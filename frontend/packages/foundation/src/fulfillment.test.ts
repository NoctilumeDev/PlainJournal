import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createFulfillmentApi } from "./fulfillment";

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

describe("fulfillment api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("encodes order lookup and confirmation paths", async () => {
    const requests: Array<{ url: string; method: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({
        url: String(input),
        method: init?.method ?? "GET",
      });
      return success({});
    }));

    const api = createFulfillmentApi(createApiClient());
    await api.fulfillmentByOrder("ORD:2026/07");
    await api.shipmentPosition("ORD:2026/07");
    await api.confirmReceipt("ORD:2026/07");

    expect(requests[0]?.url).toContain("/api/v1/fulfillment/orders/ORD%3A2026%2F07");
    expect(requests[0]?.method).toBe("GET");
    expect(requests[1]?.url).toContain(
      "/api/v1/fulfillment/orders/ORD%3A2026%2F07/position",
    );
    expect(requests[1]?.method).toBe("GET");
    expect(requests[2]?.url).toContain(
      "/api/v1/fulfillment/orders/ORD%3A2026%2F07/confirm-receipt",
    );
    expect(requests[2]?.method).toBe("POST");
  });

  it("uses distinct customer-return and warehouse command paths", async () => {
    const requests: Array<{
      url: string;
      method: string;
      body: BodyInit | null | undefined;
    }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({
        url: String(input),
        method: init?.method ?? "GET",
        body: init?.body,
      });
      return success({});
    }));

    const api = createFulfillmentApi(createApiClient());
    await api.submitReturnShipment("RET:2026/07", "SF", "SF-001");
    await api.startPicking("FUL:2026/07");
    await api.receiveReturn("RET:2026/07");
    await api.inspectReturn("RET:2026/07", "外观与数量验收通过");

    expect(requests[0]?.url).toContain("/fulfillment/returns/RET%3A2026%2F07/shipment");
    expect(requests[1]?.url).toContain("/fulfillment/admin/orders/FUL%3A2026%2F07/picking");
    expect(requests[2]?.url).toContain("/fulfillment/admin/returns/RET%3A2026%2F07/receive");
    expect(requests[3]?.url).toContain("/fulfillment/admin/returns/RET%3A2026%2F07/inspect");
    expect(requests.every((request) => request.method === "POST")).toBe(true);
  });

  it("encodes bounded admin GEO queries and rebuild commands", async () => {
    const requests: Array<{ url: string; method: string }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({
        url: String(input),
        method: init?.method ?? "GET",
      });
      return success({});
    }));

    const api = createFulfillmentApi(createApiClient());
    await api.nearbyShipmentPositions({
      longitude: "120.155100",
      latitude: "30.274100",
      radiusMeters: 25000,
      limit: 20,
    });
    await api.rebuildShipmentGeoCache(1000);

    const nearby = new URL(requests[0]?.url ?? "", "http://local");
    expect(nearby.pathname).toBe("/api/v1/fulfillment/admin/geo/nearby");
    expect(nearby.searchParams.get("longitude")).toBe("120.155100");
    expect(nearby.searchParams.get("radiusMeters")).toBe("25000");
    expect(nearby.searchParams.get("limit")).toBe("20");
    expect(requests[0]?.method).toBe("GET");
    expect(requests[1]?.url).toContain(
      "/api/v1/fulfillment/admin/geo/cache/rebuild?limit=1000",
    );
    expect(requests[1]?.method).toBe("POST");
  });

  it("preserves the explicit idempotency key for exception resolution", async () => {
    const requests: Array<{
      url: string;
      init: RequestInit | undefined;
    }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({});
    }));

    const api = createFulfillmentApi(createApiClient());
    await api.resolveFulfillmentException(
      "FUL:2026/07",
      "fulfillment-exception:command:1",
      "Physical parcel checked",
    );

    expect(requests[0]?.url).toContain(
      "/api/v1/fulfillment/admin/orders/FUL%3A2026%2F07/exception/resolve",
    );
    expect(requests[0]?.init?.method).toBe("POST");
    expect(new Headers(requests[0]?.init?.headers).get("Idempotency-Key"))
      .toBe("fulfillment-exception:command:1");
    expect(requests[0]?.init?.body).toBe(JSON.stringify({
      reason: "Physical parcel checked",
    }));
  });
});
