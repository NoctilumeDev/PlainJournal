import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { Address, AddressInput, BusinessId } from "@plain-journal/foundation";

import {
  AddressAccessChangedError,
  type AddressAccessContext,
  useAddressStore,
} from "./addressStore";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-30T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function access(
  ownerId = "2079000000000000999",
  accessToken = "access-token-a",
): AddressAccessContext {
  return { authenticated: true, ownerId, accessToken };
}

function address(
  id: BusinessId,
  overrides: Partial<Address> = {},
): Address {
  return {
    id,
    recipientName: "Test Customer",
    phone: "+86 13800000000",
    province: "浙江省",
    provinceCode: "330000",
    city: "杭州市",
    cityCode: "330100",
    district: "西湖区",
    districtCode: "330106",
    detailAddress: "文三路 1 号",
    postalCode: "310000",
    defaultAddress: false,
    version: 0,
    createdAt: "2026-07-30T00:00:00Z",
    updatedAt: "2026-07-30T00:00:00Z",
    ...overrides,
  };
}

function input(overrides: Partial<AddressInput> = {}): AddressInput {
  return {
    recipientName: "Test Customer",
    phone: "+86 13800000000",
    province: "浙江省",
    provinceCode: "330000",
    city: "杭州市",
    cityCode: "330100",
    district: "西湖区",
    districtCode: "330106",
    detailAddress: "文三路 1 号",
    postalCode: "310000",
    setDefault: true,
    ...overrides,
  };
}

describe("address entity store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("waits for Identity confirmation, reloads server facts and preserves string ids", async () => {
    let saved: Address[] = [];
    const fetchMock = vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(request), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname === "/api/v1/identity/addresses" && method === "POST") {
        saved = [address("2079000000000000888", {
          ...JSON.parse(String(init?.body)) as AddressInput,
          defaultAddress: true,
        })];
        return success(saved[0]);
      }
      if (url.pathname === "/api/v1/identity/addresses" && method === "GET") {
        return success(saved);
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const addresses = useAddressStore();
    await addresses.create(access(), input());

    expect(addresses.addresses).toHaveLength(1);
    expect(addresses.defaultAddress?.id).toBe("2079000000000000888");
    expect(addresses.activeOwnerId).toBe("2079000000000000999");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization"))
      .toBe("Bearer access-token-a");
  });

  it("treats address version as a returned server fact and does not invent a client version field", async () => {
    const addressId = "2079000000000000888";
    let saved = address(addressId, { defaultAddress: true, version: 3 });
    let updateBody: Record<string, unknown> | null = null;
    const fetchMock = vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(request), "http://local");
      const method = init?.method ?? "GET";
      if (url.pathname.endsWith(`/${addressId}`) && method === "PUT") {
        updateBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
        saved = address(addressId, {
          ...updateBody,
          defaultAddress: true,
          version: 4,
        });
        return success(saved);
      }
      if (url.pathname === "/api/v1/identity/addresses" && method === "GET") {
        return success([saved]);
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const addresses = useAddressStore();
    await addresses.update(access(), addressId, input({ detailAddress: "文三路 2 号" }));

    expect(updateBody).not.toHaveProperty("version");
    expect(addresses.addresses[0]?.version).toBe(4);
    expect(addresses.addresses[0]?.detailAddress).toBe("文三路 2 号");
  });

  it("clears the previous owner immediately and rejects a late response from that owner", async () => {
    const firstOwner = access("2079000000000000999", "token-a");
    const secondOwner = access("2079000000000001999", "token-b");
    let resolveFirst!: (response: Response) => void;
    const firstResponse = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    const fetchMock = vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get("Authorization");
      if (authorization === "Bearer token-a") {
        return firstResponse;
      }
      if (authorization === "Bearer token-b") {
        return success([address("2079000000000002888", {
          recipientName: "Second Owner",
          detailAddress: "中山路 2 号",
          defaultAddress: true,
        })]);
      }
      throw new Error(`Unexpected authorization: ${authorization}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const addresses = useAddressStore();
    const firstLoad = addresses.load(firstOwner);
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    await addresses.load(secondOwner);
    expect(addresses.activeOwnerId).toBe(secondOwner.ownerId);
    expect(addresses.addresses.map((item) => item.recipientName)).toEqual(["Second Owner"]);

    resolveFirst(success([address("2079000000000001888", {
      recipientName: "First Owner",
      defaultAddress: true,
    })]));
    await expect(firstLoad).rejects.toBeInstanceOf(AddressAccessChangedError);
    expect(addresses.addresses.map((item) => item.recipientName)).toEqual(["Second Owner"]);
  });

  it("clears owner facts when unauthenticated", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success([address("2079000000000000888", { defaultAddress: true })])));
    const addresses = useAddressStore();
    await addresses.load(access());
    await addresses.load({
      authenticated: false,
      ownerId: null,
      accessToken: null,
    });

    expect(addresses.activeOwnerId).toBeNull();
    expect(addresses.addresses).toEqual([]);
  });

  it("does not invite a duplicate mutation when Identity confirmed but reload failed", async () => {
    let requests = 0;
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      requests += 1;
      if ((init?.method ?? "GET") === "POST") {
        return success(address("2079000000000000888", { defaultAddress: true }));
      }
      return new Response(JSON.stringify({
        code: "SERVICE_UNAVAILABLE",
        message: "temporarily unavailable",
        data: null,
        timestamp: "2026-07-30T00:00:00Z",
      }), {
        status: 503,
        headers: { "Content-Type": "application/json" },
      });
    }));

    const addresses = useAddressStore();
    await expect(addresses.create(access(), input())).resolves.toMatchObject({
      id: "2079000000000000888",
    });
    expect(requests).toBe(2);
    expect(addresses.error).toContain("已确认地址修改");
    expect(addresses.error).toContain("勿重复提交");
    expect(addresses.errorTone).toBe("attention");
  });

  it("marks a lost mutation response as unknown and requires an authoritative reload", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      code: "SERVICE_UNAVAILABLE",
      message: "response unavailable",
      data: null,
      timestamp: "2026-08-02T00:00:00Z",
    }), {
      status: 503,
      headers: { "Content-Type": "application/json" },
    })));

    const addresses = useAddressStore();
    await expect(addresses.create(access(), input())).rejects.toThrow();

    expect(addresses.errorTone).toBe("unknown");
    expect(addresses.error).toContain("先重新读取地址");
    expect(addresses.error).toContain("再决定是否重试");
  });
});
