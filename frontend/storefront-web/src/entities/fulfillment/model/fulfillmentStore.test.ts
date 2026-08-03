import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  Fulfillment,
  ShipmentPosition,
} from "@plain-journal/foundation";

import {
  FulfillmentAccessChangedError,
  useFulfillmentsStore,
  type FulfillmentAccessContext,
} from "../index";

const USER_A = "2079000000000000999";
const USER_B = "2079000000000001999";
const ORDER_NO = "ORD2079000000000000001";
const FULFILLMENT_NO = "FUL2079000000000000002";

const ACCESS_A: FulfillmentAccessContext = {
  authenticated: true,
  ownerId: USER_A,
  accessToken: "access-a",
};
const ACCESS_B: FulfillmentAccessContext = {
  authenticated: true,
  ownerId: USER_B,
  accessToken: "access-b",
};

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-30T00:00:00Z",
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
    timestamp: "2026-07-30T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function fulfillmentFixture(
  overrides: Partial<Fulfillment> = {},
): Fulfillment {
  return {
    fulfillmentNo: FULFILLMENT_NO,
    orderNo: ORDER_NO,
    userId: USER_A,
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
    status: "SHIPPED",
    carrier: "MOCK_EXPRESS",
    trackingNo: "TRACK-001",
    history: [],
    traces: [],
    version: 3,
    createdAt: "2026-07-30T00:00:00Z",
    updatedAt: "2026-07-30T00:01:00Z",
    pickedAt: "2026-07-30T00:00:20Z",
    packedAt: "2026-07-30T00:00:40Z",
    shippedAt: "2026-07-30T00:01:00Z",
    signedAt: null,
    ...overrides,
  };
}

function positionFixture(
  overrides: Partial<ShipmentPosition> = {},
): ShipmentPosition {
  return {
    fulfillmentNo: FULFILLMENT_NO,
    orderNo: ORDER_NO,
    externalEventId: "TRACE-001",
    nodeType: "TRANSIT",
    locationName: "杭州市",
    longitude: "120.155100",
    latitude: "30.274100",
    occurredAt: "2026-07-30T00:02:00Z",
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe("Fulfillment entity facts", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("uses the captured access token and stores only the requested owner fact", async () => {
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      expect(new URL(String(input), "http://local").pathname)
        .toBe(`/api/v1/fulfillment/orders/${ORDER_NO}`);
      expect(new Headers(init?.headers).get("Authorization"))
        .toBe("Bearer access-a");
      return success(fulfillmentFixture());
    }));

    const store = useFulfillmentsStore();
    const value = await store.loadForOrder(ACCESS_A, ORDER_NO);

    expect(value?.status).toBe("SHIPPED");
    expect(store.activeOwnerId).toBe(USER_A);
    expect(store.fulfillmentForOrder(ORDER_NO)?.userId).toBe(USER_A);
  });

  it("loads position independently and removes stale position after an authoritative 404", async () => {
    let available = true;
    vi.stubGlobal("fetch", vi.fn(async () => {
      if (!available) {
        return failure(404, "POSITION_NOT_AVAILABLE", "position not available");
      }
      return success(positionFixture());
    }));

    const store = useFulfillmentsStore();
    expect((await store.loadPosition(ACCESS_A, ORDER_NO))?.externalEventId)
      .toBe("TRACE-001");

    available = false;
    expect(await store.loadPosition(ACCESS_A, ORDER_NO)).toBeNull();
    expect(store.positionForOrder(ORDER_NO)).toBeNull();
    expect(store.positionError).toBeNull();
  });

  it("does not fabricate a fulfillment when the owner query returns 404", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(404, "RESOURCE_NOT_FOUND", "fulfillment not found")));

    const store = useFulfillmentsStore();
    expect(await store.loadForOrder(ACCESS_A, ORDER_NO)).toBeNull();
    expect(store.error).toBeNull();
    expect(store.fulfillmentForOrder(ORDER_NO)).toBeNull();
  });

  it("rejects an old account response after an owner switch and keeps the new owner clean", async () => {
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));

    const store = useFulfillmentsStore();
    const oldRequest = store.loadForOrder(ACCESS_A, ORDER_NO);
    store.synchronizeAccess(ACCESS_B);
    response.resolve(success(fulfillmentFixture()));

    await expect(oldRequest).rejects.toBeInstanceOf(FulfillmentAccessChangedError);
    expect(store.activeOwnerId).toBe(USER_B);
    expect(store.fulfillments).toEqual([]);
  });

  it("rejects an old token response without erasing same-owner authoritative facts", async () => {
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));

    const store = useFulfillmentsStore();
    store.recordAuthoritativeFulfillment(ACCESS_A, ORDER_NO, fulfillmentFixture());
    const oldRequest = store.loadForOrder(ACCESS_A, ORDER_NO);
    store.synchronizeAccess({ ...ACCESS_A, accessToken: "access-a-rotated" });
    response.resolve(success(fulfillmentFixture({ status: "SIGNED" })));

    await expect(oldRequest).rejects.toBeInstanceOf(FulfillmentAccessChangedError);
    expect(store.fulfillmentForOrder(ORDER_NO)?.status).toBe("SHIPPED");
  });

  it("does not write a response whose order or owner identity mismatches the request", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success(fulfillmentFixture({
      orderNo: "ORD-OTHER",
      userId: USER_B,
    }))));

    const store = useFulfillmentsStore();
    expect(await store.loadForOrder(ACCESS_A, ORDER_NO)).toBeNull();
    expect(store.error).toContain("不一致");
    expect(store.fulfillments).toEqual([]);
  });

  it("does not write a position tied to a different fulfillment", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success(positionFixture({ fulfillmentNo: "FUL-OTHER" }))));

    const store = useFulfillmentsStore();
    store.recordAuthoritativeFulfillment(ACCESS_A, ORDER_NO, fulfillmentFixture());

    expect(await store.loadPosition(ACCESS_A, ORDER_NO)).toBeNull();
    expect(store.positionError).toContain("不一致");
    expect(store.positions).toEqual([]);
  });
});
