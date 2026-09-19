import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { createAuthenticatedApiClient } from "../shared/api";
import {
  hasAnyRole,
  hasWorkspaceRole,
  useStaffSessionStore,
} from "./session";

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

describe("admin workspace role gate", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("admits only the bounded staff roles for this workspace", () => {
    expect(hasWorkspaceRole(["ADMIN"])).toBe(true);
    expect(hasWorkspaceRole(["OPERATOR"])).toBe(true);
    expect(hasWorkspaceRole(["WAREHOUSE"])).toBe(true);
    expect(hasWorkspaceRole(["CUSTOMER", "FINANCE"])).toBe(false);
  });

  it("checks each route against its explicit service roles", () => {
    expect(hasAnyRole(["WAREHOUSE"], ["ADMIN", "WAREHOUSE"])).toBe(true);
    expect(hasAnyRole(["OPERATOR"], ["ADMIN", "WAREHOUSE"])).toBe(false);
    expect(hasAnyRole(["ADMIN"], ["ADMIN"])).toBe(true);
  });

  it("silently clears an expired stored staff session before login", async () => {
    localStorage.setItem("plain-journal:staff-refresh-token:v1", "refresh-token-old");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(failure(
      401,
      "INVALID_REFRESH_TOKEN",
      "The refresh token is invalid or expired",
    )));

    const session = useStaffSessionStore();
    await session.restore();

    expect(session.authenticated).toBe(false);
    expect(session.error).toBeNull();
    expect(localStorage.getItem("plain-journal:staff-refresh-token:v1"))
      .toBeNull();
  });

  it("re-checks staff roles after runtime refresh and never replays after revocation", async () => {
    const tokens = {
      tokenType: "Bearer",
      accessToken: "staff-access",
      expiresIn: 900,
      refreshToken: "staff-refresh",
    };
    const rotatedTokens = {
      ...tokens,
      accessToken: "staff-access-rotated",
      refreshToken: "staff-refresh-rotated",
    };
    const staffProfile = {
      id: "2079000000000000999",
      email: "operator@example.com",
      displayName: "Operator",
      status: "ACTIVE",
      roles: ["OPERATOR"],
    };
    const customerProfile = {
      ...staffProfile,
      roles: ["CUSTOMER"],
    };
    let protectedCalls = 0;
    let logoutAuthorization: string | null = "not-observed";
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(request), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/identity/auth/login")) {
        return success(tokens);
      }
      if (path.endsWith("/identity/auth/refresh")) {
        expect(authorization).toBeNull();
        return success(rotatedTokens);
      }
      if (path.endsWith("/identity/auth/logout")) {
        logoutAuthorization = authorization;
        return success(null);
      }
      if (path.endsWith("/identity/me")) {
        return success(authorization === "Bearer staff-access-rotated"
          ? customerProfile
          : staffProfile);
      }
      if (path === "/api/v1/admin/protected") {
        protectedCalls += 1;
        return failure(401, "UNAUTHORIZED", "expired");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const session = useStaffSessionStore();
    await expect(session.login({
      email: staffProfile.email,
      password: "OperatorPass123",
    })).resolves.toBe(true);

    await expect(createAuthenticatedApiClient(null).request(
      "/api/v1/admin/protected",
      { method: "POST", body: JSON.stringify({ command: "one" }) },
    )).rejects.toMatchObject({ status: 401 });

    expect(protectedCalls).toBe(1);
    expect(logoutAuthorization).toBeNull();
    expect(session.authenticated).toBe(false);
    expect(session.accessDenied?.roles).toEqual(["CUSTOMER"]);
    expect(localStorage.getItem("plain-journal:staff-session:v2")).toBeNull();
  });
});
