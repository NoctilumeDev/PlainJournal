import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";

describe("createApiClient", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("unwraps the shared success envelope", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "OK",
      message: "success",
      data: { id: "2079000000000000001" },
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 200, headers: { "Content-Type": "application/json" } })));

    const client = createApiClient();

    await expect(client.request<{ id: string }>("/api/test"))
      .resolves.toEqual({ id: "2079000000000000001" });
  });

  it("keeps backend failures explicit", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "RESOURCE_NOT_FOUND",
      message: "Product was not found",
      data: null,
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 404, headers: { "Content-Type": "application/json" } })));

    const client = createApiClient();
    const request = client.request("/api/test");

    await expect(request).rejects.toMatchObject({
      kind: "http",
      code: "RESOURCE_NOT_FOUND",
      status: 404,
    });
  });

  it("does not turn transport failure into success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("connection refused")));

    const client = createApiClient();

    await expect(client.request("/api/test")).rejects.toMatchObject({
      kind: "network",
      code: "NETWORK_UNAVAILABLE",
    });
  });
});
