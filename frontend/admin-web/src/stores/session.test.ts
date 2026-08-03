import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  hasAnyRole,
  hasWorkspaceRole,
  useStaffSessionStore,
} from "./session";

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
});
