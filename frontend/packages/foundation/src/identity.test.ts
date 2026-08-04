import { describe, expect, it, vi } from "vitest";

import type { ApiClient, BusinessId } from "./api";
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

  it("maps every identity command to its owned endpoint", async () => {
    const request = vi.fn().mockResolvedValue(undefined);
    const api = createIdentityApi({ request } as ApiClient);
    const addressId = "2079000000000000010" as BusinessId;
    const address = {
      recipientName: "Reader",
      phone: "13800000000",
      province: "浙江省",
      provinceCode: "330000",
      city: "杭州市",
      cityCode: "330100",
      district: "西湖区",
      districtCode: "330106",
      detailAddress: "青荷路 1 号",
      postalCode: "310000",
      setDefault: true,
    };

    await api.register({
      email: "reader@example.com",
      password: "Strong-password-1",
      displayName: "Reader",
    });
    await api.login({
      email: "reader@example.com",
      password: "Strong-password-1",
    });
    await api.logout("refresh-1");
    await api.addresses();
    await api.createAddress(address);
    await api.updateAddress(addressId, address);
    await api.setDefaultAddress(addressId);
    await api.deleteAddress(addressId);

    expect(request.mock.calls).toEqual([
      ["/api/v1/identity/auth/register", {
        method: "POST",
        body: JSON.stringify({
          email: "reader@example.com",
          password: "Strong-password-1",
          displayName: "Reader",
        }),
      }],
      ["/api/v1/identity/auth/login", {
        method: "POST",
        body: JSON.stringify({
          email: "reader@example.com",
          password: "Strong-password-1",
        }),
      }],
      ["/api/v1/identity/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken: "refresh-1" }),
      }],
      ["/api/v1/identity/addresses"],
      ["/api/v1/identity/addresses", {
        method: "POST",
        body: JSON.stringify(address),
      }],
      [`/api/v1/identity/addresses/${addressId}`, {
        method: "PUT",
        body: JSON.stringify(address),
      }],
      [`/api/v1/identity/addresses/${addressId}/default`, { method: "POST" }],
      [`/api/v1/identity/addresses/${addressId}`, { method: "DELETE" }],
    ]);
  });
});
