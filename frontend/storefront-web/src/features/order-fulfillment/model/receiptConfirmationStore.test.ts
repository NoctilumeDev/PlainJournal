import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { Fulfillment } from "@plain-journal/foundation";

import {
  FulfillmentAccessChangedError,
  useFulfillmentsStore,
  type FulfillmentAccessContext,
} from "../../../entities/fulfillment";
import { useReceiptConfirmationStore } from "./receiptConfirmationStore";

const USER_A = "2079000000000000999";
const USER_B = "2079000000000001999";
const ORDER_NO = "ORD2079000000000000001";

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
  status = "SHIPPED",
  overrides: Partial<Fulfillment> = {},
): Fulfillment {
  return {
    fulfillmentNo: "FUL2079000000000000002",
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
    status,
    carrier: "MOCK_EXPRESS",
    trackingNo: "TRACK-001",
    history: [],
    traces: [],
    version: status === "SIGNED" ? 4 : 3,
    createdAt: "2026-07-30T00:00:00Z",
    updatedAt: "2026-07-30T00:02:00Z",
    pickedAt: "2026-07-30T00:00:20Z",
    packedAt: "2026-07-30T00:00:40Z",
    shippedAt: "2026-07-30T00:01:00Z",
    signedAt: status === "SIGNED" ? "2026-07-30T00:02:00Z" : null,
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe("receipt confirmation feature", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("recovers a lost POST response only after Fulfillment returns SIGNED", async () => {
    const methods: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const method = init?.method ?? "GET";
      methods.push(method);
      if (method === "POST") {
        throw new Error("response lost after commit");
      }
      return success(fulfillmentFixture("SIGNED"));
    }));

    const facts = useFulfillmentsStore();
    facts.recordAuthoritativeFulfillment(
      ACCESS_A,
      ORDER_NO,
      fulfillmentFixture(),
    );
    const confirmations = useReceiptConfirmationStore();

    const value = await confirmations.confirmReceipt(ACCESS_A, ORDER_NO);

    expect(methods).toEqual(["POST", "GET"]);
    expect(value?.status).toBe("SIGNED");
    expect(confirmations.unknownOrderNo).toBeNull();
    expect(facts.fulfillmentForOrder(ORDER_NO)?.status).toBe("SIGNED");
  });

  it("keeps the order unknown when POST and owner read do not prove SIGNED", async () => {
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => init?.method === "POST"
      ? failure(503, "REMOTE_DEPENDENCY_UNAVAILABLE", "response unavailable")
      : success(fulfillmentFixture())));

    const facts = useFulfillmentsStore();
    facts.recordAuthoritativeFulfillment(
      ACCESS_A,
      ORDER_NO,
      fulfillmentFixture(),
    );
    const confirmations = useReceiptConfirmationStore();

    const value = await confirmations.confirmReceipt(ACCESS_A, ORDER_NO);

    expect(value?.status).toBe("SHIPPED");
    expect(confirmations.unknownOrderNo).toBe(ORDER_NO);
    expect(confirmations.confirmationError).toContain("尚未确认");
  });

  it("coalesces concurrent confirmation intents into one POST", async () => {
    const postResponse = deferred<Response>();
    let postCount = 0;
    vi.stubGlobal("fetch", vi.fn((
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      if (init?.method !== "POST") {
        throw new Error("unexpected read");
      }
      postCount += 1;
      return postResponse.promise;
    }));

    const facts = useFulfillmentsStore();
    facts.recordAuthoritativeFulfillment(
      ACCESS_A,
      ORDER_NO,
      fulfillmentFixture(),
    );
    const confirmations = useReceiptConfirmationStore();
    const first = confirmations.confirmReceipt(ACCESS_A, ORDER_NO);
    const second = confirmations.confirmReceipt(ACCESS_A, ORDER_NO);

    expect(postCount).toBe(1);
    postResponse.resolve(success(fulfillmentFixture("SIGNED")));
    await expect(first).resolves.toMatchObject({ status: "SIGNED" });
    await expect(second).resolves.toMatchObject({ status: "SIGNED" });
  });

  it("rejects a late confirmation after account switch and writes no A fact into B", async () => {
    const postResponse = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => postResponse.promise));

    const facts = useFulfillmentsStore();
    facts.recordAuthoritativeFulfillment(
      ACCESS_A,
      ORDER_NO,
      fulfillmentFixture(),
    );
    const confirmations = useReceiptConfirmationStore();
    const pending = confirmations.confirmReceipt(ACCESS_A, ORDER_NO);
    confirmations.synchronizeAccess(ACCESS_B);
    postResponse.resolve(success(fulfillmentFixture("SIGNED")));

    await expect(pending).rejects.toBeInstanceOf(FulfillmentAccessChangedError);
    expect(facts.activeOwnerId).toBe(USER_B);
    expect(facts.fulfillments).toEqual([]);
    expect(confirmations.unknownOrderNo).toBeNull();
  });

  it("does not send a command for a non-confirmable authoritative status", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const facts = useFulfillmentsStore();
    facts.recordAuthoritativeFulfillment(
      ACCESS_A,
      ORDER_NO,
      fulfillmentFixture("CREATED"),
    );
    const confirmations = useReceiptConfirmationStore();

    const value = await confirmations.confirmReceipt(ACCESS_A, ORDER_NO);

    expect(value?.status).toBe("CREATED");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(confirmations.confirmationError).toContain("不能确认收货");
  });
});
