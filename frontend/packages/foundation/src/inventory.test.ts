import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createInventoryApi } from "./inventory";

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

describe("inventory admin api", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("encodes business ids and sends the stable movement number", async () => {
    const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), init });
      return success({});
    }));

    const api = createInventoryApi(createApiClient());
    await api.stockPosition("2079000000000000001", "2079000000000000011");
    await api.adjustStock({
      movementNo: "admin-stock:00000000-0000-0000-0000-000000000001",
      warehouseId: "2079000000000000001",
      skuId: "2079000000000000011",
      quantityDelta: 5,
      reason: "M4 管理端验收",
    });

    expect(requests[0]?.url).toContain(
      "/warehouses/2079000000000000001/stocks/2079000000000000011",
    );
    expect(requests[1]?.init?.body).toContain(
      "admin-stock:00000000-0000-0000-0000-000000000001",
    );
  });
});
