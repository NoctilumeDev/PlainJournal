import { describe, expect, it, vi } from "vitest";

import type { ApiClient } from "./api";
import { createIdentityApi } from "./identity";

describe("createIdentityApi", () => {
  it("keeps browser-facing identity ids as strings", async () => {
    const request = vi.fn().mockResolvedValue({
      id: "2079000000000000001",
      email: "reader@example.com",
      displayName: "Reader",
      status: "ACTIVE",
      roles: ["CUSTOMER"],
    });
    const api = createIdentityApi({ request } as ApiClient);

    const profile = await api.currentUser();

    expect(profile.id).toBe("2079000000000000001");
    expect(request).toHaveBeenCalledWith("/api/v1/identity/me");
  });

  it("sends refresh rotation through the explicit identity endpoint", async () => {
    const request = vi.fn().mockResolvedValue({
      tokenType: "Bearer",
      accessToken: "access-2",
      expiresIn: 900,
      refreshToken: "refresh-2",
    });
    const api = createIdentityApi({ request } as ApiClient);

    await api.refresh("refresh-1");

    expect(request).toHaveBeenCalledWith("/api/v1/identity/auth/refresh", {
      method: "POST",
      body: JSON.stringify({ refreshToken: "refresh-1" }),
    });
  });
});
