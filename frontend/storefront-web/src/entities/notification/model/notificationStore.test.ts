import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  NotificationAccessChangedError,
  type NotificationAccessContext,
  useNotificationStore,
} from "./notificationStore";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
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
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("customer notification entity", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps keyset pagination and unread facts in the Notification owner domain", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success({
        items: [notification("9223372036854775806")],
        nextCursor: "cursor-2",
        hasMore: true,
      }))
      .mockResolvedValueOnce(success({ count: 2 }))
      .mockResolvedValueOnce(success({
        items: [notification("9223372036854775805", "READ")],
        nextCursor: null,
        hasMore: false,
      }))
      .mockResolvedValueOnce(success({ count: 1 }));
    vi.stubGlobal("fetch", fetchMock);

    const store = useNotificationStore();
    await store.load(access());
    await store.load(access(), { append: true });

    expect(store.notifications.map((item) => item.id)).toEqual([
      "9223372036854775806",
      "9223372036854775805",
    ]);
    expect(store.unreadCount).toBe(1);
    expect(store.hasMore).toBe(false);
    expect(String(fetchMock.mock.calls[2]?.[0])).toContain("cursor=cursor-2");
  });

  it("rejects a late response after the owner changes", async () => {
    let resolveFirst!: (value: Response) => void;
    const firstPage = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (request: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(request), "http://local").pathname;
      const authorization = new Headers(init?.headers).get("Authorization");
      if (path.endsWith("/unread-count")) {
        return success({ count: authorization === "Bearer token-b" ? 1 : 0 });
      }
      if (authorization === "Bearer token-a") {
        return firstPage;
      }
      return success({
        items: [notification("200", "UNREAD", "Second owner")],
        nextCursor: null,
        hasMore: false,
      });
    }));

    const store = useNotificationStore();
    const first = store.load(access());
    await vi.waitFor(() => expect(store.loading).toBe(true));
    await store.load(access("2079000000000002999", "token-b"));

    resolveFirst(success({
      items: [notification("100", "UNREAD", "First owner")],
      nextCursor: null,
      hasMore: false,
    }));
    await expect(first).rejects.toBeInstanceOf(NotificationAccessChangedError);
    expect(store.notifications.map((item) => item.title)).toEqual(["Second owner"]);
    expect(store.activeOwnerId).toBe("2079000000000002999");
  });

  it("keeps unread visible after an unknown read result until the list is authoritative", async () => {
    const item = notification("9223372036854775806");
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(success({
        items: [item],
        nextCursor: null,
        hasMore: false,
      }))
      .mockResolvedValueOnce(success({ count: 1 }))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "response lost",
      ))
      .mockResolvedValueOnce(success({
        items: [{ ...item, status: "READ", readAt: "2026-08-03T01:00:00Z" }],
        nextCursor: null,
        hasMore: false,
      }))
      .mockResolvedValueOnce(success({ count: 0 }));
    vi.stubGlobal("fetch", fetchMock);

    const store = useNotificationStore();
    await store.load(access());
    const marked = await store.markRead(access(), item.id);

    expect(marked).toBe(false);
    expect(store.notifications[0]?.status).toBe("UNREAD");
    expect(store.readUnknown).toBe(true);
    expect(store.pendingReadId).toBe(item.id);

    await expect(store.reconcilePendingRead(access())).resolves.toBe("confirmed-read");
    expect(store.notifications[0]?.status).toBe("READ");
    expect(store.unreadCount).toBe(0);
    expect(store.readUnknown).toBe(false);
  });

  it("refuses a numeric snowflake ID before a read command can be built", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success({
        items: [{ ...notification("1"), id: 9223372036854776000 }],
        nextCursor: null,
        hasMore: false,
      }))
      .mockResolvedValueOnce(success({ count: 1 })));

    const store = useNotificationStore();
    await store.load(access());

    expect(store.notifications).toEqual([]);
    expect(store.error).toContain("非字符串业务 ID");
  });

  it("keeps the read result unknown when the authoritative reread is unavailable", async () => {
    const item = notification("9223372036854775806");
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(success({
        items: [item],
        nextCursor: null,
        hasMore: false,
      }))
      .mockResolvedValueOnce(success({ count: 1 }))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "read response lost",
      ))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "list unavailable",
      ))
      .mockResolvedValueOnce(failure(
        503,
        "SERVICE_UNAVAILABLE",
        "count unavailable",
      )));

    const store = useNotificationStore();
    await store.load(access());
    await store.markRead(access(), item.id);

    await expect(store.reconcilePendingRead(access())).resolves.toBe("unavailable");
    expect(store.readUnknown).toBe(true);
    expect(store.pendingReadId).toBe(item.id);
    expect(store.notifications[0]?.status).toBe("UNREAD");
  });
});

function access(
  ownerId = "2079000000000000999",
  accessToken = "token-a",
): NotificationAccessContext {
  return { authenticated: true, ownerId, accessToken };
}

function notification(
  id: string,
  status = "UNREAD",
  title = "订单已发货",
) {
  return {
    id,
    templateCode: "SHIPMENT_DISPATCHED",
    referenceType: "ORDER",
    referenceNo: "ORD-001",
    title,
    content: "订单 ORD-001 已经发货。",
    status,
    readAt: status === "READ" ? "2026-08-03T01:00:00Z" : null,
    createdAt: id.endsWith("6")
      ? "2026-08-03T01:00:00Z"
      : "2026-08-03T00:00:00Z",
  };
}
