import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  AdjustStockInput,
  StockPosition,
  Warehouse,
} from "@plain-journal/foundation";

import {
  useAdminInventoryStore,
  type AdminInventoryAccessContext,
} from "./adminInventoryStore";

const OPERATOR_ID = "2086000000000000001";
const ACCESS: AdminInventoryAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "warehouse-token",
};
const OTHER_ACCESS: AdminInventoryAccessContext = {
  authorized: true,
  operatorId: "2086000000000000002",
  accessToken: "other-warehouse-token",
};
const WAREHOUSE_ID = "2086000000000000010";
const SKU_ID = "2086000000000000020";
const STORAGE_KEY =
  `plain-journal:admin-inventory:pending-command:v1:${OPERATOR_ID}`;

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

function stockFixture(
  overrides: Partial<StockPosition> = {},
): StockPosition {
  return {
    warehouseId: WAREHOUSE_ID,
    skuId: SKU_ID,
    onHand: 15,
    reserved: 3,
    available: 12,
    version: 4,
    ...overrides,
  };
}

function warehouseFixture(
  overrides: Partial<Warehouse> = {},
): Warehouse {
  return {
    id: WAREHOUSE_ID,
    code: "HANGZHOU",
    name: "杭州中心仓",
    status: "ACTIVE",
    version: 0,
    ...overrides,
  };
}

function fillAdjustment(store: ReturnType<typeof useAdminInventoryStore>) {
  store.adjustment.warehouseId = WAREHOUSE_ID;
  store.adjustment.skuId = SKU_ID;
  store.adjustment.quantityDelta = 5;
  store.adjustment.reason = "V6.4.3 浏览器库存调整";
}

describe("admin inventory entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps a lost adjustment unknown and retries the exact movement and payload", async () => {
    const requests: AdjustStockInput[] = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      requests.push(JSON.parse(String(init?.body)) as AdjustStockInput);
      if (attempts === 1) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success(stockFixture());
    }));

    const store = useAdminInventoryStore();
    store.synchronizeAccess(ACCESS);
    fillAdjustment(store);
    const movementNo = store.adjustment.movementNo;

    await store.adjustStock(ACCESS);

    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCommand?.commandKey).toBe(movementNo);
    expect(localStorage.getItem(STORAGE_KEY)).toContain(movementNo);

    await store.retryPending(ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(requests).toHaveLength(2);
    expect(requests[0]).toEqual(requests[1]);
    expect(requests[0]).toEqual({
      movementNo,
      warehouseId: WAREHOUSE_ID,
      skuId: SKU_ID,
      quantityDelta: 5,
      reason: "V6.4.3 浏览器库存调整",
    });
    expect(store.adjustment.movementNo).not.toBe(movementNo);
    expect(store.stock).toEqual(stockFixture());
  });

  it("restores an unresolved adjustment with its original payload", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const first = useAdminInventoryStore();
    first.synchronizeAccess(ACCESS);
    fillAdjustment(first);
    const movementNo = first.adjustment.movementNo;
    await first.adjustStock(ACCESS);

    setActivePinia(createPinia());
    const restored = useAdminInventoryStore();
    restored.synchronizeAccess(ACCESS);

    expect(restored.commandPhase).toBe("unknown");
    expect(restored.pendingCommand?.commandKey).toBe(movementNo);
    expect(restored.adjustment).toMatchObject({
      movementNo,
      warehouseId: WAREHOUSE_ID,
      skuId: SKU_ID,
      quantityDelta: 5,
      reason: "V6.4.3 浏览器库存调整",
    });
  });

  it("does not attribute a stock reread to an unresolved movement", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
    ) => {
      attempts += 1;
      const path = new URL(String(input), "http://localhost").pathname;
      if (path.endsWith("/stocks/adjustments")) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      expect(path).toBe(
        `/api/v1/inventory/admin/warehouses/${WAREHOUSE_ID}/stocks/${SKU_ID}`,
      );
      return success(stockFixture({ onHand: 20, version: 5 }));
    }));

    const store = useAdminInventoryStore();
    store.synchronizeAccess(ACCESS);
    fillAdjustment(store);
    const movementNo = store.adjustment.movementNo;
    await store.adjustStock(ACCESS);
    await store.readPendingAuthority(ACCESS);

    expect(attempts).toBe(2);
    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("不公开 movementNo");
    expect(store.pendingCommand?.commandKey).toBe(movementNo);
    expect(store.stock).toEqual(stockFixture({ onHand: 20, version: 5 }));
  });

  it("settles a lost warehouse creation only after the unique fact is reread", async () => {
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      if (init?.method === "POST") {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      return success([warehouseFixture()]);
    }));

    const store = useAdminInventoryStore();
    store.synchronizeAccess(ACCESS);
    store.warehouseForm.code = "hangzhou";
    store.warehouseForm.name = "杭州中心仓";

    await store.createWarehouse(ACCESS);
    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCommand?.referenceNo).toBe("HANGZHOU");

    await store.readPendingAuthority(ACCESS);

    expect(attempts).toBe(2);
    expect(store.commandPhase).toBe("accepted");
    expect(store.pendingCommand).toBeNull();
    expect(store.warehouses).toEqual([warehouseFixture()]);
    expect(store.warehouseForm).toEqual({ code: "", name: "" });
  });

  it("does not write stale warehouse facts after the operator changes", async () => {
    let resolveWarehouses!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveWarehouses = resolve;
      })));

    const store = useAdminInventoryStore();
    const request = store.loadWarehouses(ACCESS);
    store.synchronizeAccess(OTHER_ACCESS);
    resolveWarehouses(success([warehouseFixture()]));

    await expect(request).resolves.toBeUndefined();
    expect(store.warehouses).toEqual([]);
  });
});
