import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  AddLogisticsTraceInput,
  Fulfillment,
  ReturnReceipt,
} from "@plain-journal/foundation";

import {
  useAdminFulfillmentStore,
  type AdminFulfillmentAccessContext,
} from "./adminFulfillmentStore";

const OPERATOR_ID = "2085000000000000001";
const ACCESS: AdminFulfillmentAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "warehouse-token",
};
const ADMIN_ACCESS: AdminFulfillmentAccessContext = {
  authorized: true,
  operatorId: "2085000000000000002",
  accessToken: "admin-token",
};
const FULFILLMENT_NO = "FUL2085000000000000003";
const RETURN_NO = "RET2085000000000000004";
const STORAGE_KEY =
  `plain-journal:admin-fulfillment:pending-command:v1:${OPERATOR_ID}`;

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

function fulfillmentFixture(
  overrides: Partial<Fulfillment> = {},
): Fulfillment {
  return {
    fulfillmentNo: FULFILLMENT_NO,
    orderNo: "ORD2085000000000000005",
    userId: "2085000000000000006",
    deliveryAddress: {
      sourceAddressId: "2085000000000000007",
      recipientName: "仓库验收顾客",
      phone: "13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "测试路 1 号",
      postalCode: "310000",
    },
    status: "SHIPPED",
    carrier: "PLAIN_EXPRESS",
    trackingNo: "TRACK-V642",
    history: [],
    traces: [],
    version: 2,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:00:00Z",
    pickedAt: "2026-08-03T00:00:00Z",
    packedAt: "2026-08-03T00:00:00Z",
    shippedAt: "2026-08-03T00:00:00Z",
    signedAt: null,
    ...overrides,
  };
}

function returnFixture(
  overrides: Partial<ReturnReceipt> = {},
): ReturnReceipt {
  return {
    returnReceiptNo: RETURN_NO,
    afterSaleNo: "AS2085000000000000008",
    orderNo: "ORD2085000000000000005",
    userId: "2085000000000000006",
    warehouseId: "2085000000000000009",
    reservationNo: "RES2085000000000000010",
    status: "RETURNING",
    refundAmount: "189.00",
    carrier: "PLAIN_EXPRESS",
    trackingNo: "RETURN-V642",
    inspectionRemark: null,
    items: [{
      lineNo: 1,
      skuId: "2085000000000000011",
      quantity: 1,
      refundableAmount: "189.00",
    }],
    version: 1,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:00:00Z",
    shippedAt: "2026-08-03T00:00:00Z",
    receivedAt: null,
    inspectedAt: null,
    ...overrides,
  };
}

describe("admin fulfillment entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps a lost trace command unknown and retries the exact event id and payload", async () => {
    const posted: Array<{
      path: string;
      payload: AddLogisticsTraceInput;
    }> = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      const payload = JSON.parse(String(init?.body)) as AddLogisticsTraceInput;
      posted.push({
        path: new URL(String(input), "http://localhost").pathname,
        payload,
      });
      if (attempts === 1) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success(fulfillmentFixture({
        status: "IN_TRANSIT",
        traces: [{
          externalEventId: payload.externalEventId,
          nodeType: payload.nodeType,
          description: payload.description,
          locationName: payload.locationName ?? null,
          longitude: payload.longitude ?? null,
          latitude: payload.latitude ?? null,
          occurredAt: payload.occurredAt,
        }],
      }));
    }));

    const store = useAdminFulfillmentStore();
    store.synchronizeAccess(ACCESS);
    const form = store.traceForm(FULFILLMENT_NO);
    form.nodeType = "TRANSIT";
    form.description = "浏览器丢失响应后保留原轨迹";
    form.locationName = "杭州分拨中心";
    form.longitude = 120.1551;
    form.latitude = 30.2741;
    const eventId = form.externalEventId;

    await store.addTrace(ACCESS, FULFILLMENT_NO);

    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCommand?.commandKey).toBe(eventId);
    const persisted = JSON.parse(
      String(localStorage.getItem(STORAGE_KEY)),
    ) as {
      requiresAuthorityRead?: boolean;
      payload: Record<string, unknown>;
    };
    expect(persisted.requiresAuthorityRead).toBe(true);
    expect(persisted.payload).toEqual({ externalEventId: eventId });

    await store.retryPending(ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(posted).toHaveLength(2);
    expect(posted[0]).toEqual(posted[1]);
    expect(posted[0]?.path).toBe(
      `/api/v1/fulfillment/admin/orders/${FULFILLMENT_NO}/traces`,
    );
    expect(store.traceForm(FULFILLMENT_NO).externalEventId).not.toBe(eventId);
  });

  it("redacts persisted coordinates and resolves a restored trace through authority", async () => {
    let eventId = "";
    const fetchMock = vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      if (init?.method === "POST") {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      expect(new URL(String(input), "http://localhost").pathname).toBe(
        `/api/v1/fulfillment/admin/orders/${FULFILLMENT_NO}`,
      );
      return success(fulfillmentFixture({
        status: "IN_TRANSIT",
        traces: [{
          externalEventId: eventId,
          nodeType: "TRANSIT",
          description: "恢复本地履约命令",
          locationName: "杭州分拨中心",
          longitude: "120.1551",
          latitude: "30.2741",
          occurredAt: "2026-08-03T00:00:00Z",
        }],
      }));
    });
    vi.stubGlobal("fetch", fetchMock);

    const first = useAdminFulfillmentStore();
    first.synchronizeAccess(ACCESS);
    const form = first.traceForm(FULFILLMENT_NO);
    form.description = "恢复本地履约命令";
    form.locationName = "杭州分拨中心";
    form.longitude = "120.1551";
    form.latitude = "30.2741";
    eventId = form.externalEventId;
    await first.addTrace(ACCESS, FULFILLMENT_NO);

    setActivePinia(createPinia());
    const restored = useAdminFulfillmentStore();
    restored.synchronizeAccess(ACCESS);

    expect(restored.commandPhase).toBe("unknown");
    expect(restored.pendingCommand?.commandKey).toBe(eventId);
    expect(restored.traceForm(FULFILLMENT_NO)).toMatchObject({
      externalEventId: eventId,
      description: "",
      locationName: "",
      longitude: "",
      latitude: "",
    });

    await restored.retryPending(ACCESS);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(restored.commandMessage).toContain("不能安全原样重试");

    await restored.readPendingAuthority(ACCESS);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(restored.commandPhase).toBe("accepted");
    expect(restored.pendingCommand).toBeNull();
  });

  it("settles a lost trace only when the authority returns the exact event identity", async () => {
    let posted: AddLogisticsTraceInput | null = null;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const path = new URL(String(input), "http://localhost").pathname;
      if (init?.method === "POST") {
        posted = JSON.parse(String(init.body)) as AddLogisticsTraceInput;
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      expect(path).toBe(
        `/api/v1/fulfillment/admin/orders/${FULFILLMENT_NO}`,
      );
      const trace = posted;
      if (!trace) {
        throw new Error("missing posted trace");
      }
      return success(fulfillmentFixture({
        status: "IN_TRANSIT",
        traces: [{
          externalEventId: trace.externalEventId,
          nodeType: trace.nodeType,
          description: trace.description,
          locationName: trace.locationName ?? null,
          longitude: trace.longitude ?? null,
          latitude: trace.latitude ?? null,
          occurredAt: trace.occurredAt,
        }],
      }));
    }));

    const store = useAdminFulfillmentStore();
    store.synchronizeAccess(ACCESS);
    store.traceForm(FULFILLMENT_NO).description = "读取权威轨迹确认";
    await store.addTrace(ACCESS, FULFILLMENT_NO);
    await store.readPendingAuthority(ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(store.commandMessage).toContain("权威事实已确认原命令");
    expect(store.pendingCommand).toBeNull();
  });

  it("treats an explicit 409 as rejected instead of result unknown", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(409, "INVALID_STATE", "cannot pick current fulfillment")));

    const store = useAdminFulfillmentStore();
    store.synchronizeAccess(ACCESS);
    await store.startPicking(ACCESS, FULFILLMENT_NO);

    expect(store.commandPhase).toBe("rejected");
    expect(store.commandMessage).toBe("cannot pick current fulfillment");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("retries exception recovery with the original command id and reason", async () => {
    const posted: Array<{ commandId: string | null; reason: string }> = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      posted.push({
        commandId: new Headers(init?.headers).get("Idempotency-Key"),
        reason: String(
          (JSON.parse(String(init?.body)) as { reason?: unknown }).reason ?? "",
        ),
      });
      if (attempts === 1) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success(fulfillmentFixture({
        status: "PICKING",
        history: [{
          fromStatus: "EXCEPTION",
          toStatus: "PICKING",
          command: "RESOLVE_EXCEPTION",
          reason: posted[0]?.reason ?? null,
          operatorType: "ADMIN",
          operatorId: String(ADMIN_ACCESS.operatorId),
          createdAt: "2026-08-03T00:00:00Z",
        }],
      }));
    }));

    const store = useAdminFulfillmentStore();
    store.synchronizeAccess(ADMIN_ACCESS);
    const form = store.resolutionForm(FULFILLMENT_NO);
    form.reason = "管理员已核对包裹与上一状态";
    const commandId = form.commandId;

    await store.resolveException(ADMIN_ACCESS, FULFILLMENT_NO);
    await store.retryPending(ADMIN_ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(posted).toEqual([
      { commandId, reason: "管理员已核对包裹与上一状态" },
      { commandId, reason: "管理员已核对包裹与上一状态" },
    ]);
  });

  it("does not write stale fulfillment facts after the operator changes", async () => {
    let resolveOrders!: (response: Response) => void;
    let resolveReturns!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn((
      input: RequestInfo | URL,
    ) => {
      const path = new URL(String(input), "http://localhost").pathname;
      return new Promise<Response>((resolve) => {
        if (path.endsWith("/admin/orders")) {
          resolveOrders = resolve;
        } else {
          resolveReturns = resolve;
        }
      });
    }));

    const store = useAdminFulfillmentStore();
    const request = store.loadFacts(ACCESS);
    store.synchronizeAccess(ADMIN_ACCESS);
    resolveOrders(success([fulfillmentFixture()]));
    resolveReturns(success([returnFixture()]));

    await expect(request).resolves.toBeUndefined();
    expect(store.fulfillments).toEqual([]);
    expect(store.returns).toEqual([]);
  });
});
