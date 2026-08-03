import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import {
  ChatAccessChangedError,
  type ChatAccessContext,
  useCustomerChatStore,
} from "./customerChatStore";

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

describe("customer Chat entity boundary", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("abandons the previous workspace so a late owner response cannot leak", async () => {
    let resolveFirst!: (response: Response) => void;
    const firstResponse = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (_request: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get("Authorization");
      if (authorization === "Bearer token-a") {
        return firstResponse;
      }
      return success([conversation("conversation-b", "owner-b")]);
    }));

    const store = useCustomerChatStore();
    const first = store.loadConversations(access("owner-a", "token-a"));
    await store.loadConversations(access("owner-b", "token-b"));
    resolveFirst(success([conversation("conversation-a", "owner-a")]));

    await expect(first).rejects.toBeInstanceOf(ChatAccessChangedError);
    expect(store.ownerId).toBe("owner-b");
    expect(store.conversations.map((item) => item.id)).toEqual(["conversation-b"]);
  });

  it("does not expose another owner's pending message content", () => {
    localStorage.setItem(
      "plain-journal:customer-chat-pending-send:v1",
      JSON.stringify({
        userId: "owner-a",
        conversationId: "conversation-a",
        clientMessageId: "chat:message:owner-a",
        content: "another owner's private content",
        createdAt: "2026-08-03T00:00:00Z",
      }),
    );

    const store = useCustomerChatStore();
    store.synchronizeAccess(access("owner-b", "token-b"));

    expect(store.pendingSend).toBeNull();
    expect(store.hasForeignPendingSend).toBe(true);
    expect(JSON.stringify(store.$state)).not.toContain("private content");
  });

  it("rejects a conversation whose customer owner does not match the token owner", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success([conversation("conversation-a", "owner-b")])));

    const store = useCustomerChatStore();
    await store.loadConversations(access("owner-a", "token-a"));

    expect(store.conversations).toEqual([]);
    expect(store.error).toContain("不属于当前账户");
  });
});

function access(
  ownerId: string,
  accessToken: string,
): ChatAccessContext {
  return {
    authenticated: true,
    ownerId,
    accessToken,
  };
}

function conversation(id: string, customerId: string) {
  return {
    id,
    conversationNo: `CHAT-${id}`,
    customerId,
    assignedAgentId: null,
    subject: "商品保养方式",
    contextType: null,
    contextId: null,
    status: "OPEN",
    lastMessageSequence: 0,
    unreadCount: 0,
    version: 0,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:00:00Z",
  };
}
