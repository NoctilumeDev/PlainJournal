import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useBagStore } from "../../../entities/guest-bag";
import { createAuthenticatedApiClient } from "../../../shared/api";
import { useSessionStore } from "./session";

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

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-07-20T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const tokens = {
  tokenType: "Bearer",
  accessToken: "access-token",
  expiresIn: 900,
  refreshToken: "refresh-token",
};

const profile = {
  id: "2079000000000000999",
  email: "reader@example.com",
  displayName: "Reader",
  status: "ACTIVE",
  roles: ["CUSTOMER"],
};

describe("customer session", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("logs in and clears only the guest quantities confirmed by Trade", async () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 2,
      coverUrl: null,
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockResolvedValueOnce(success([]));
    vi.stubGlobal("fetch", fetchMock);

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });

    expect(session.authenticated).toBe(true);
    expect(session.bagMergeStatus).toBe("succeeded");
    expect(bag.items).toHaveLength(0);
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token");
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Authorization"))
      .toBe("Bearer access-token");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization"))
      .toBeNull();
  });

  it("keeps the local bag and stable retry request when merge transport is unknown", async () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockRejectedValueOnce(new TypeError("connection reset")));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const firstKey = bag.pendingMerge?.key;

    expect(session.authenticated).toBe(true);
    expect(session.bagMergeStatus).toBe("unknown");
    expect(bag.items).toHaveLength(1);
    expect(firstKey).toBeTruthy();
    expect(bag.prepareMerge(profile.id)?.key).toBe(firstKey);
  });

  it("treats a merge 5xx as unknown because Trade may already have committed", async () => {
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "temporarily unavailable",
      )));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });

    expect(session.authenticated).toBe(true);
    expect(session.bagMergeStatus).toBe("unknown");
    expect(bag.items).toHaveLength(1);
    expect(bag.pendingMerge?.userId).toBe(profile.id);
  });

  it("coalesces concurrent merge attempts for one owner into one Trade request", async () => {
    localStorage.setItem("plain-journal:customer-refresh-token:v1", "refresh-token-old");
    let resolveMerge!: (response: Response) => void;
    const mergeResponse = new Promise<Response>((resolve) => {
      resolveMerge = resolve;
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockReturnValueOnce(mergeResponse);
    vi.stubGlobal("fetch", fetchMock);

    const session = useSessionStore();
    await session.restore();
    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    });

    const first = session.mergeGuestBag();
    const second = session.mergeGuestBag();
    expect(fetchMock).toHaveBeenCalledTimes(3);

    resolveMerge(success([]));
    await Promise.all([first, second]);
    expect(bag.items).toEqual([]);
    expect(session.bagMergeStatus).toBe("succeeded");
  });

  it("does not let a late merge response clear another owner's device facts", async () => {
    const alternateProfile = {
      ...profile,
      id: "2079000000000002999",
      email: "reader-two@example.com",
      displayName: "Second Reader",
    };
    const alternateTokens = {
      ...tokens,
      accessToken: "access-token-b",
      refreshToken: "refresh-token-b",
    };
    let resolveFirstMerge!: (response: Response) => void;
    const firstMergeResponse = new Promise<Response>((resolve) => {
      resolveFirstMerge = resolve;
    });
    const mergeAuthorizations: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(request), "http://local");
      const authorization = new Headers(init?.headers).get("Authorization") ?? "";
      if (url.pathname.endsWith("/identity/auth/login")) {
        const input = JSON.parse(String(init?.body)) as { email: string };
        return success(input.email === alternateProfile.email ? alternateTokens : tokens);
      }
      if (url.pathname.endsWith("/identity/me")) {
        return success(authorization.includes("access-token-b")
          ? alternateProfile
          : profile);
      }
      if (url.pathname.endsWith("/trade/cart/guest-merge")) {
        mergeAuthorizations.push(authorization);
        return firstMergeResponse;
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    }));

    const bag = useBagStore();
    bag.addItem({
      productId: "2079000000000000001",
      skuId: "2079000000000000011",
      productTitle: "帆布通勤袋",
      skuName: "自然色 / 中号",
      unitPrice: "189.00",
      quantity: 1,
      coverUrl: null,
    });
    const session = useSessionStore();
    const firstLogin = session.login({
      email: profile.email,
      password: "ReaderPass123",
    });
    await vi.waitFor(() => {
      expect(session.bagMergeStatus).toBe("pending");
    });

    await session.clearLocalOnly();
    await session.login({
      email: alternateProfile.email,
      password: "ReaderPass123",
    });
    expect(session.profile?.id).toBe(alternateProfile.id);
    expect(session.bagMergeStatus).toBe("ownership-conflict");

    resolveFirstMerge(success([]));
    await firstLogin;

    expect(session.profile?.id).toBe(alternateProfile.id);
    expect(session.bagMergeStatus).toBe("ownership-conflict");
    expect(bag.items).toHaveLength(1);
    expect(bag.pendingMerge?.userId).toBe(profile.id);
    expect(mergeAuthorizations).toEqual(["Bearer access-token"]);
  });

  it("rotates the stored refresh token while restoring the session", async () => {
    localStorage.setItem("plain-journal:customer-refresh-token:v1", "refresh-token-old");
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success({
        ...tokens,
        accessToken: "access-token-new",
        refreshToken: "refresh-token-new",
      }))
      .mockResolvedValueOnce(success(profile));
    vi.stubGlobal("fetch", fetchMock);

    const session = useSessionStore();
    await session.restore();

    expect(session.authenticated).toBe(true);
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token-new");
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)))
      .toEqual({ refreshToken: "refresh-token-old" });
  });

  it("silently clears an expired stored session before showing the login page", async () => {
    localStorage.setItem("plain-journal:customer-refresh-token:v1", "refresh-token-old");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(failure(
      401,
      "INVALID_REFRESH_TOKEN",
      "The refresh token is invalid or expired",
    )));

    const session = useSessionStore();
    await session.restore();

    expect(session.authenticated).toBe(false);
    expect(session.error).toBeNull();
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBeNull();
  });

  it("coalesces concurrent restore requests into one refresh rotation", async () => {
    localStorage.setItem("plain-journal:customer-refresh-token:v1", "refresh-token-old");
    let resolveRefresh: ((response: Response) => void) | undefined;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    const fetchMock = vi.fn()
      .mockReturnValueOnce(refreshResponse)
      .mockResolvedValueOnce(success(profile));
    vi.stubGlobal("fetch", fetchMock);

    const session = useSessionStore();
    const first = session.restore();
    const second = session.restore();

    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    resolveRefresh?.(success({
      ...tokens,
      accessToken: "access-token-new",
      refreshToken: "refresh-token-new",
    }));
    await Promise.all([first, second]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(session.authenticated).toBe(true);
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token-new");
  });

  it("keeps a refresh credential when restore transport is unknown", async () => {
    localStorage.setItem("plain-journal:customer-refresh-token:v1", "refresh-token-old");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(new TypeError("connection reset")));

    const session = useSessionStore();
    await session.restore();

    expect(session.authenticated).toBe(false);
    expect(session.error).toContain("结果未知");
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token-old");
  });

  it("refreshes once, re-reads identity, and replays a protected request once", async () => {
    const rotatedTokens = {
      ...tokens,
      accessToken: "access-token-rotated",
      refreshToken: "refresh-token-rotated",
    };
    const refreshedProfile = { ...profile, displayName: "Reader Updated" };
    const protectedAuthorizations: string[] = [];
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(request), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        expect(authorization).toBeNull();
        return success(rotatedTokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(authorization === "Bearer access-token-rotated"
          ? refreshedProfile
          : profile);
      }
      if (path === "/api/v1/protected") {
        protectedAuthorizations.push(authorization ?? "");
        return authorization === "Bearer access-token-rotated"
          ? success({ recovered: true })
          : failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const before = JSON.parse(String(
      localStorage.getItem("plain-journal:customer-session:v2"),
    )) as { authority: string };

    await expect(createAuthenticatedApiClient(null).request("/api/v1/protected"))
      .resolves.toEqual({ recovered: true });

    const after = JSON.parse(String(
      localStorage.getItem("plain-journal:customer-session:v2"),
    )) as { authority: string; refreshToken: string };
    expect(refreshCalls).toBe(1);
    expect(protectedAuthorizations).toEqual([
      "Bearer access-token",
      "Bearer access-token-rotated",
    ]);
    expect(after.authority).toBe(before.authority);
    expect(after.refreshToken).toBe("refresh-token-rotated");
    expect(session.profile?.displayName).toBe("Reader Updated");
    expect(session.recoveryState).toBe("idle");
  });

  it("coalesces simultaneous protected 401 responses into one local refresh", async () => {
    const rotatedTokens = {
      ...tokens,
      accessToken: "access-token-rotated",
      refreshToken: "refresh-token-rotated",
    };
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(request), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        return success(rotatedTokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(profile);
      }
      if (path.startsWith("/api/v1/protected/")) {
        return authorization === "Bearer access-token-rotated"
          ? success({ path })
          : failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const client = createAuthenticatedApiClient(null);

    await expect(Promise.all([
      client.request("/api/v1/protected/one"),
      client.request("/api/v1/protected/two"),
    ])).resolves.toHaveLength(2);
    expect(refreshCalls).toBe(1);
  });

  it("never replays an old account request under a newly logged-in authority", async () => {
    const alternateProfile = {
      ...profile,
      id: "2079000000000002999",
      email: "reader-two@example.com",
      displayName: "Second Reader",
    };
    const alternateTokens = {
      ...tokens,
      accessToken: "access-token-b",
      refreshToken: "refresh-token-b",
    };
    let resolveProtected!: (response: Response) => void;
    const protectedResponse = new Promise<Response>((resolve) => {
      resolveProtected = resolve;
    });
    let protectedCalls = 0;
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(request), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/identity/auth/login")) {
        const input = JSON.parse(String(init?.body)) as { email: string };
        return success(input.email === alternateProfile.email ? alternateTokens : tokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(authorization === "Bearer access-token-b"
          ? alternateProfile
          : profile);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        return success(tokens);
      }
      if (path === "/api/v1/protected-write") {
        protectedCalls += 1;
        return protectedResponse;
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const originalAuthority = session.requestAuthority;
    const pending = createAuthenticatedApiClient(null).request(
      "/api/v1/protected-write",
      { method: "POST", body: JSON.stringify({ owner: profile.id }) },
    );
    await vi.waitFor(() => expect(protectedCalls).toBe(1));

    await session.clearLocalOnly();
    await session.login({
      email: alternateProfile.email,
      password: "ReaderPass123",
    });
    expect(session.requestAuthority).not.toBe(originalAuthority);
    resolveProtected(failure(401, "UNAUTHORIZED", "expired"));

    await expect(pending).rejects.toMatchObject({ status: 401 });
    expect(protectedCalls).toBe(1);
    expect(refreshCalls).toBe(0);
    expect(session.authenticated).toBe(true);
    expect(session.profile?.id).toBe(alternateProfile.id);
    expect(session.recoveryState).toBe("idle");
  });

  it("does not replay a business request when refresh remains unknown", async () => {
    let protectedCalls = 0;
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL) => {
      const path = new URL(String(request), "http://local").pathname;
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(profile);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        throw new TypeError("response lost");
      }
      if (path === "/api/v1/protected-write") {
        protectedCalls += 1;
        return failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });

    await expect(createAuthenticatedApiClient(null).request(
      "/api/v1/protected-write",
      { method: "POST", body: JSON.stringify({ intent: "one" }) },
    )).rejects.toMatchObject({ code: "REFRESH_OUTCOME_UNKNOWN" });

    expect(refreshCalls).toBe(2);
    expect(protectedCalls).toBe(1);
    expect(session.recoveryState).toBe("outcome-unknown");
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token");
  });

  it("requires reauthentication when a lost refresh response is followed by 401", async () => {
    let refreshCalls = 0;
    let protectedCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL) => {
      const path = new URL(String(request), "http://local").pathname;
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(profile);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        if (refreshCalls === 1) {
          throw new TypeError("response lost after commit");
        }
        return failure(401, "INVALID_REFRESH_TOKEN", "already consumed");
      }
      if (path === "/api/v1/protected-write") {
        protectedCalls += 1;
        return failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });

    await expect(createAuthenticatedApiClient(null).request(
      "/api/v1/protected-write",
      { method: "POST", body: JSON.stringify({ intent: "one" }) },
    )).rejects.toMatchObject({ status: 401 });

    expect(refreshCalls).toBe(2);
    expect(protectedCalls).toBe(1);
    expect(session.recoveryState).toBe("reauth-required");
    expect(localStorage.getItem("plain-journal:customer-session:v2")).toBeNull();
  });

  it("does not let a stale tab failure clear a newer shared session", async () => {
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL) => {
      const path = new URL(String(request), "http://local").pathname;
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/me")) {
        return success(profile);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        refreshCalls += 1;
        return failure(401, "INVALID_REFRESH_TOKEN", "invalid");
      }
      if (path === "/api/v1/protected") {
        return failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    const newer = {
      authority: "newer-tab-authority",
      refreshToken: "newer-tab-refresh",
    };
    localStorage.setItem(
      "plain-journal:customer-session:v2",
      JSON.stringify(newer),
    );
    localStorage.setItem(
      "plain-journal:customer-refresh-token:v1",
      newer.refreshToken,
    );

    await expect(createAuthenticatedApiClient(null).request("/api/v1/protected"))
      .rejects.toMatchObject({ status: 401 });

    expect(refreshCalls).toBe(0);
    expect(JSON.parse(String(
      localStorage.getItem("plain-journal:customer-session:v2"),
    ))).toEqual(newer);
    expect(session.recoveryState).toBe("reauth-required");
  });

  it("does not claim logout success when the server result is unknown", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockRejectedValueOnce(new TypeError("connection reset")));
    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });

    await expect(session.logout()).rejects.toThrow();

    expect(session.authenticated).toBe(true);
    expect(session.logoutError).toBeTruthy();
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBe("refresh-token");
  });

  it("clears only the current device after an unknown logout when explicitly requested", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success(tokens))
      .mockResolvedValueOnce(success(profile))
      .mockRejectedValueOnce(new TypeError("connection reset")));
    const session = useSessionStore();
    await session.login({ email: profile.email, password: "ReaderPass123" });
    await expect(session.logout()).rejects.toThrow();

    await session.clearLocalOnly();

    expect(session.authenticated).toBe(false);
    expect(session.logoutError).toBeNull();
    expect(localStorage.getItem("plain-journal:customer-refresh-token:v1"))
      .toBeNull();
  });
});
