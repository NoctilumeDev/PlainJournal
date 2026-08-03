import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { ReturnReceipt } from "@plain-journal/foundation";

import {
  useReturnReceiptsStore,
  type ReturnReceiptAccessContext,
} from "./returnReceiptStore";

const USER_ID = "2079000000000000999";
const OTHER_USER_ID = "2079000000000001999";
const RETURN_NO = "RET2079000000000000001";
const ACCESS: ReturnReceiptAccessContext = {
  authenticated: true,
  ownerId: USER_ID,
  accessToken: "access-token-a",
};
const OTHER_ACCESS: ReturnReceiptAccessContext = {
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

function fixture(
  status = "WAIT_SHIPMENT",
  userId = USER_ID,
  returnReceiptNo = RETURN_NO,
): ReturnReceipt {
  return {
    returnReceiptNo,
    afterSaleNo: userId === USER_ID ? "AS-A" : "AS-B",
    orderNo: userId === USER_ID ? "ORD-A" : "ORD-B",
    userId,
    warehouseId: "2079000000000000004",
    reservationNo: "RES2079000000000000005",
    status,
    refundAmount: "398.00",
    carrier: status === "WAIT_SHIPMENT" ? null : "SF",
    trackingNo: status === "WAIT_SHIPMENT" ? null : "SF-001",
    inspectionRemark: null,
    items: [],
    version: status === "WAIT_SHIPMENT" ? 0 : 1,
    createdAt: "2026-07-21T00:00:00Z",
    updatedAt: "2026-07-21T00:01:00Z",
    shippedAt: status === "WAIT_SHIPMENT" ? null : "2026-07-21T00:01:00Z",
    receivedAt: null,
    inspectedAt: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

describe("customer return-receipt entity", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("recovers a lost shipment response only from the matching Fulfillment fact", async () => {
    let reads = 0;
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        throw new TypeError("response lost");
      }
      reads += 1;
      return success(fixture(reads === 1 ? "WAIT_SHIPMENT" : "RETURNING"));
    }));

    const store = useReturnReceiptsStore();
    const value = await store.submitShipment(ACCESS, RETURN_NO, "sf", "SF-001");

    expect(reads).toBe(2);
    expect(value?.status).toBe("RETURNING");
    expect(store.submissionUnknown).toBe(false);
  });

  it("keeps the result unknown when the authoritative read stays at WAIT_SHIPMENT", async () => {
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        throw new TypeError("response lost");
      }
      return success(fixture("WAIT_SHIPMENT"));
    }));

    const store = useReturnReceiptsStore();
    const value = await store.submitShipment(ACCESS, RETURN_NO, "SF", "SF-001");

    expect(value?.status).toBe("WAIT_SHIPMENT");
    expect(store.submissionUnknown).toBe(true);
    expect(store.submissionError).toContain("尚未确认");
  });

  it("coalesces identical double submits into one POST", async () => {
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        posts += 1;
        return success(fixture("RETURNING"));
      }
      return success(fixture("WAIT_SHIPMENT"));
    }));

    const store = useReturnReceiptsStore();
    const first = store.submitShipment(ACCESS, RETURN_NO, "SF", "SF-001");
    const second = store.submitShipment(ACCESS, RETURN_NO, "SF", "SF-001");
    await Promise.all([first, second]);

    expect(posts).toBe(1);
  });

  it("drops an old owner response after an account switch", async () => {
    const oldResponse = deferred<Response>();
    let request = 0;
    vi.stubGlobal("fetch", vi.fn(async () => {
      request += 1;
      return request === 1
        ? oldResponse.promise
        : success([fixture("WAIT_SHIPMENT", OTHER_USER_ID, "RET-B")]);
    }));

    const store = useReturnReceiptsStore();
    const oldLoad = store.load(ACCESS);
    await store.load(OTHER_ACCESS);
    oldResponse.resolve(success([fixture()]));
    await oldLoad;

    expect(store.activeOwnerId).toBe(OTHER_USER_ID);
    expect(store.returnReceipts.map((value) => value.returnReceiptNo)).toEqual(["RET-B"]);
  });

  it("does not store a response that belongs to another account", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([
      fixture("WAIT_SHIPMENT", OTHER_USER_ID, "RET-B"),
    ])));

    const store = useReturnReceiptsStore();
    await store.load(ACCESS);

    expect(store.returnReceipts).toEqual([]);
    expect(store.error).toContain("账户或请求不一致");
  });
});
