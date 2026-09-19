import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, createApiClient } from "./api";

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

  it("preserves an explicitly supplied authorization header without a token provider", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "OK",
      message: "success",
      data: null,
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await createApiClient().request("/api/internal", {
      headers: { Authorization: "Internal service-token" },
    });

    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization"))
      .toBe("Internal service-token");
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

  it("replays an unauthorized request once after explicit recovery", async () => {
    let token = "expired-access";
    const requestAuthority = "session-authority";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: "UNAUTHORIZED",
        message: "expired",
        data: null,
        timestamp: "2026-07-20T00:00:00Z",
      }), { status: 401, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: "OK",
        message: "success",
        data: { recovered: true },
        timestamp: "2026-07-20T00:00:01Z",
      }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const onUnauthorized = vi.fn().mockImplementation(async () => {
      token = "fresh-access";
      return "retry" as const;
    });
    const client = createApiClient({
      tokenProvider: () => token,
      requestAuthorityProvider: () => requestAuthority,
      onUnauthorized,
    });

    await expect(client.request("/api/protected", {
      method: "POST",
      body: JSON.stringify({ intent: "same-request" }),
    })).resolves.toEqual({ recovered: true });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization"))
      .toBe("Bearer expired-access");
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Authorization"))
      .toBe("Bearer fresh-access");
    expect(onUnauthorized).toHaveBeenCalledOnce();
    expect(onUnauthorized).toHaveBeenCalledWith(expect.objectContaining({
      accessToken: "expired-access",
      requestAuthority,
      retryAttempted: false,
    }));
  });

  it("never replays more than once and reports the replayed 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => new Response(JSON.stringify({
      code: "UNAUTHORIZED",
      message: "expired",
      data: null,
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 401, headers: { "Content-Type": "application/json" } })));
    const onUnauthorized = vi.fn().mockResolvedValue("retry");
    const client = createApiClient({ onUnauthorized });

    await expect(client.request("/api/protected")).rejects.toMatchObject({
      kind: "http",
      status: 401,
    });

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(onUnauthorized).toHaveBeenNthCalledWith(2, expect.objectContaining({
      retryAttempted: true,
    }));
  });

  it("does not replay when recovery cannot prove success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "UNAUTHORIZED",
      message: "expired",
      data: null,
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 401, headers: { "Content-Type": "application/json" } })));
    const client = createApiClient({
      onUnauthorized: async () => {
        throw new ApiError("network", "REFRESH_OUTCOME_UNKNOWN", "unknown");
      },
    });

    await expect(client.request("/api/protected", {
      method: "POST",
      body: JSON.stringify({ operation: "write" }),
    })).rejects.toMatchObject({ code: "REFRESH_OUTCOME_UNKNOWN" });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("does not refresh for a domain-specific 401", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "INVALID_SIGNATURE",
      message: "invalid callback signature",
      data: null,
      timestamp: "2026-07-20T00:00:00Z",
    }), { status: 401, headers: { "Content-Type": "application/json" } })));
    const onUnauthorized = vi.fn().mockResolvedValue("retry");

    await expect(createApiClient({ onUnauthorized }).request("/api/domain"))
      .rejects.toMatchObject({ code: "INVALID_SIGNATURE", status: 401 });
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(fetch).toHaveBeenCalledOnce();
  });
});
