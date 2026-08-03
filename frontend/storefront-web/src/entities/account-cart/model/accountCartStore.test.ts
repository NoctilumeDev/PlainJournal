import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type { CartItem } from "@plain-journal/foundation";

import {
  AccountCartAccessChangedError,
  AccountCartMutationBusyError,
  type AccountCartAccessContext,
  useAccountCartStore,
} from "./accountCartStore";

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

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-07-30T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function access(
  ownerId = "2079000000000000999",
  accessToken = "access-token-a",
): AccountCartAccessContext {
  return { authenticated: true, ownerId, accessToken };
}

function item(overrides: Partial<CartItem> = {}): CartItem {
  return {
    id: "2079000000000000777",
    productId: "2079000000000000001",
    skuId: "2079000000000000011",
    productTitle: "帆布通勤袋",
    skuName: "自然色 / 中号",
    specJson: "{}",
    unitPrice: "189.00",
    quantity: 2,
    selected: true,
    ...overrides,
  };
}

describe("account cart entity store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("coalesces duplicate reads for one owner and keeps string identities", async () => {
    let resolveRead!: (response: Response) => void;
    const response = new Promise<Response>((resolve) => {
      resolveRead = resolve;
    });
    const fetchMock = vi.fn((
      _request: RequestInfo | URL,
      _init?: RequestInit,
    ) => response);
    vi.stubGlobal("fetch", fetchMock);

    const cart = useAccountCartStore();
    const first = cart.load(access());
    const second = cart.load(access());
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveRead(success([item()]));
    await Promise.all([first, second]);

    expect(cart.activeOwnerId).toBe("2079000000000000999");
    expect(cart.items[0]?.id).toBe("2079000000000000777");
    expect(cart.itemCount).toBe(2);
    expect(cart.selectedSubtotal).toBe("378.00");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Authorization"))
      .toBe("Bearer access-token-a");
  });

  it("clears the old owner immediately and rejects its late response", async () => {
    let resolveFirst!: (response: Response) => void;
    const firstResponse = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get("Authorization");
      if (authorization === "Bearer token-a") {
        return firstResponse;
      }
      if (authorization === "Bearer token-b") {
        return success([item({
          id: "2079000000000002777",
          productId: "2079000000000002001",
          skuId: "2079000000000002011",
          productTitle: "第二账户手账",
        })]);
      }
      throw new Error(`Unexpected authorization: ${authorization}`);
    }));

    const cart = useAccountCartStore();
    const firstLoad = cart.load(access("2079000000000000999", "token-a"));
    await vi.waitFor(() => {
      expect(cart.loading).toBe(true);
    });

    await cart.load(access("2079000000000002999", "token-b"));
    expect(cart.items.map((candidate) => candidate.productTitle)).toEqual(["第二账户手账"]);

    resolveFirst(success([item({ productTitle: "第一账户商品" })]));
    await expect(firstLoad).rejects.toBeInstanceOf(AccountCartAccessChangedError);
    expect(cart.activeOwnerId).toBe("2079000000000002999");
    expect(cart.items.map((candidate) => candidate.productTitle)).toEqual(["第二账户手账"]);
  });

  it("maps confirmed state replacement and deletion without optimistic claims", async () => {
    const requests: Array<{ method: string; body: unknown }> = [];
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      requests.push({
        method,
        body: init?.body ? JSON.parse(String(init.body)) : null,
      });
      if (method === "GET") {
        return success([item()]);
      }
      if (method === "PUT") {
        return success(item({ quantity: 4, selected: false }));
      }
      if (method === "DELETE") {
        return success(null);
      }
      throw new Error(`Unexpected method: ${method}`);
    }));

    const cart = useAccountCartStore();
    await cart.load(access());
    await cart.updateItem(access(), cart.items[0]!, {
      quantity: 4,
      selected: false,
    });

    expect(cart.items[0]).toMatchObject({ quantity: 4, selected: false });
    expect(cart.mutationStatus).toBe("succeeded");
    expect(requests[1]).toEqual({
      method: "PUT",
      body: {
        productId: "2079000000000000001",
        quantity: 4,
        selected: false,
      },
    });

    await cart.removeItem(access(), cart.items[0]!);
    expect(cart.items).toEqual([]);
    expect(requests[2]?.method).toBe("DELETE");
  });

  it("keeps the last confirmed cart when a mutation result is unknown and resolves by reading", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success([item()]))
      .mockRejectedValueOnce(new TypeError("connection reset"))
      .mockResolvedValueOnce(success([item({ quantity: 5 })]));
    vi.stubGlobal("fetch", fetchMock);

    const cart = useAccountCartStore();
    await cart.load(access());
    await expect(cart.updateItem(access(), cart.items[0]!, {
      quantity: 5,
      selected: true,
    })).rejects.toThrow();

    expect(cart.items[0]?.quantity).toBe(2);
    expect(cart.mutationStatus).toBe("unknown");
    expect(cart.mutationMessage).toContain("重新读取");

    await cart.load(access(), { force: true });
    expect(cart.items[0]?.quantity).toBe(5);
    expect(cart.mutationStatus).toBe("idle");
  });

  it("classifies a server-side 5xx as unknown instead of a known failure", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success([item()]))
      .mockResolvedValueOnce(failure(503, "SERVICE_UNAVAILABLE", "temporarily unavailable")));

    const cart = useAccountCartStore();
    await cart.load(access());
    await expect(cart.removeItem(access(), cart.items[0]!)).rejects.toThrow();

    expect(cart.items).toHaveLength(1);
    expect(cart.mutationStatus).toBe("unknown");
  });

  it("serializes user writes so response ordering cannot reverse the latest intent", async () => {
    let resolveMutation!: (response: Response) => void;
    const mutationResponse = new Promise<Response>((resolve) => {
      resolveMutation = resolve;
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success([item()]))
      .mockReturnValueOnce(mutationResponse));

    const cart = useAccountCartStore();
    await cart.load(access());
    const firstMutation = cart.updateItem(access(), cart.items[0]!, {
      quantity: 3,
      selected: true,
    });

    await expect(cart.updateItem(access(), cart.items[0]!, {
      quantity: 4,
      selected: true,
    })).rejects.toBeInstanceOf(AccountCartMutationBusyError);

    resolveMutation(success(item({ quantity: 3 })));
    await firstMutation;
    expect(cart.items[0]?.quantity).toBe(3);
  });

  it("forces a post-merge read instead of accepting an older in-flight snapshot", async () => {
    let resolveOldRead!: (response: Response) => void;
    const oldRead = new Promise<Response>((resolve) => {
      resolveOldRead = resolve;
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockReturnValueOnce(oldRead)
      .mockResolvedValueOnce(success([item({ quantity: 4 })])));

    const cart = useAccountCartStore();
    const preMergeRead = cart.load(access());
    await cart.load(access(), { force: true });
    resolveOldRead(success([item({ quantity: 2 })]));

    await expect(preMergeRead).rejects.toBeInstanceOf(AccountCartAccessChangedError);
    expect(cart.items[0]?.quantity).toBe(4);
  });

  it("clears owner facts when authentication disappears", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => success([item()])));
    const cart = useAccountCartStore();
    await cart.load(access());
    await cart.load({
      authenticated: false,
      ownerId: null,
      accessToken: null,
    });

    expect(cart.activeOwnerId).toBeNull();
    expect(cart.items).toEqual([]);
  });
});
