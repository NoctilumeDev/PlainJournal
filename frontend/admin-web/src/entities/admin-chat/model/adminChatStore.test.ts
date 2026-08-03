import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  ChatConversation,
  ChatMessage,
  ChatMessagePage,
} from "@plain-journal/foundation";

import {
  useAdminChatStore,
  type AdminChatAccessContext,
} from "./adminChatStore";

const OPERATOR_ID = "2087000000000000001";
const OTHER_OPERATOR_ID = "2087000000000000002";
const CUSTOMER_ID = "2087000000000000101";
const CONVERSATION_ID = "2087000000000000201";
const MESSAGE_ID = "2087000000000000301";
const ACCESS: AdminChatAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "admin-token",
};
const UPDATED_ACCESS: AdminChatAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "admin-token-refreshed",
};
const OTHER_ACCESS: AdminChatAccessContext = {
  authorized: true,
  operatorId: OTHER_OPERATOR_ID,
  accessToken: "other-admin-token",
};
const STORAGE_KEY =
  `plain-journal:admin-chat:pending-send:v2:${OPERATOR_ID}`;

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

function conversationFixture(
  overrides: Partial<ChatConversation> = {},
): ChatConversation {
  return {
    id: CONVERSATION_ID,
    conversationNo: "CHAT-20260803-0001",
    customerId: CUSTOMER_ID,
    assignedAgentId: null,
    subject: "确认商品保养方式",
    contextType: null,
    contextId: null,
    status: "OPEN",
    lastMessageSequence: 1,
    unreadCount: 1,
    version: 0,
    createdAt: "2026-08-03T00:00:00Z",
    updatedAt: "2026-08-03T00:01:00Z",
    ...overrides,
  };
}

function messageFixture(
  overrides: Partial<ChatMessage> = {},
): ChatMessage {
  return {
    id: MESSAGE_ID,
    conversationId: CONVERSATION_ID,
    senderId: CUSTOMER_ID,
    clientMessageId: "chat:message:customer-fixture",
    sequence: 1,
    messageType: "TEXT",
    content: "请问可以水洗吗？",
    attachments: [],
    status: "STORED",
    createdAt: "2026-08-03T00:02:00Z",
    ...overrides,
  };
}

function messagePage(
  items: ChatMessage[] = [messageFixture()],
): ChatMessagePage {
  return {
    items,
    nextBeforeSequence: null,
    hasMore: false,
  };
}

function requestPath(input: RequestInfo | URL): string {
  return new URL(String(input), "http://127.0.0.1").pathname;
}

function requestMethod(init?: RequestInit): string {
  return String(init?.method ?? "GET").toUpperCase();
}

function readFact(lastReadMessageId = MESSAGE_ID) {
  return {
    conversationId: CONVERSATION_ID,
    lastReadMessageId,
    lastReadSequence: 1,
    readAt: "2026-08-03T00:03:00Z",
  };
}

describe("admin chat entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("keeps queue summaries visible but never requests private messages before claim", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const path = requestPath(input);
      requests.push(`${requestMethod(init)} ${path}`);
      if (path === "/api/v1/chat/conversations") {
        return success([conversationFixture()]);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, CONVERSATION_ID);

    expect(store.activeConversation?.id).toBe(CONVERSATION_ID);
    expect(store.messages).toEqual([]);
    expect(requests).toEqual(["GET /api/v1/chat/conversations"]);
  });

  it("recovers a lost claim only after the authoritative member fact permits reading", async () => {
    let claimed = false;
    let claimPosts = 0;
    const messageGets: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://127.0.0.1");
      const method = requestMethod(init);
      if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
        return success([conversationFixture({
          assignedAgentId: claimed ? OPERATOR_ID : null,
          version: claimed ? 1 : 0,
        })]);
      }
      if (
        method === "POST"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}/claim`
      ) {
        claimPosts += 1;
        claimed = true;
        return failure(
          503,
          "SERVICE_UNAVAILABLE",
          "claim response lost after commit",
        );
      }
      if (
        method === "GET"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}`
      ) {
        return success(conversationFixture({
          assignedAgentId: OPERATOR_ID,
          version: 1,
        }));
      }
      if (
        method === "GET"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        messageGets.push(url.pathname);
        return success(messagePage());
      }
      if (
        method === "POST"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}/read`
      ) {
        return success(readFact());
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, CONVERSATION_ID);
    expect(store.messages).toEqual([]);

    await store.claimConversation(ACCESS, CONVERSATION_ID);

    expect(claimPosts).toBe(1);
    expect(messageGets).toHaveLength(1);
    expect(store.messages[0]?.content).toBe("请问可以水洗吗？");
    expect(store.operationPhase).toBe("accepted");
    expect(store.operationMessage).toContain("权威成员事实");
  });

  it("keeps a lost reply unknown and retries the exact original client key and body", async () => {
    const submitted: Array<{ clientMessageId: string; content: string }> = [];
    let sendAttempts = 0;
    let recoveryGets = 0;
    const assigned = conversationFixture({
      assignedAgentId: OPERATOR_ID,
      version: 1,
    });
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://127.0.0.1");
      const method = requestMethod(init);
      if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
        return success([assigned]);
      }
      if (
        method === "GET"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}`
      ) {
        return success(assigned);
      }
      if (
        method === "GET"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        recoveryGets += 1;
        return success(messagePage());
      }
      if (
        method === "POST"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}/read`
      ) {
        return success(readFact());
      }
      if (
        method === "POST"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        sendAttempts += 1;
        const payload = JSON.parse(String(init?.body)) as {
          clientMessageId: string;
          content: string;
        };
        submitted.push({
          clientMessageId: payload.clientMessageId,
          content: payload.content,
        });
        return sendAttempts === 1
          ? failure(
              503,
              "SERVICE_UNAVAILABLE",
              "send request did not reach Chat",
            )
          : success(messageFixture({
              id: "2087000000000000302",
              senderId: OPERATOR_ID,
              clientMessageId: payload.clientMessageId,
              sequence: 2,
              content: payload.content,
            }));
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, CONVERSATION_ID);
    await store.sendText(ACCESS, "已核对商品保养说明。");

    const pendingKey = store.pendingSend?.clientMessageId;
    expect(store.sendUnknown).toBe(true);
    expect(pendingKey).toBeTruthy();
    expect(localStorage.getItem(STORAGE_KEY)).toContain(String(pendingKey));

    await store.retryPendingSend(ACCESS);

    expect(submitted).toEqual([
      {
        clientMessageId: pendingKey,
        content: "已核对商品保养说明。",
      },
      {
        clientMessageId: pendingKey,
        content: "已核对商品保养说明。",
      },
    ]);
    expect(recoveryGets).toBeGreaterThanOrEqual(3);
    expect(store.sendUnknown).toBe(false);
    expect(store.pendingSend).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("isolates pending replies by operator and restores only the original owner's key", async () => {
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://127.0.0.1");
      const method = requestMethod(init);
      if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
        return success([conversationFixture({
          assignedAgentId: OPERATOR_ID,
          version: 1,
        })]);
      }
      if (
        method === "GET"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}`
      ) {
        return success(conversationFixture({
          assignedAgentId: OPERATOR_ID,
          version: 1,
        }));
      }
      if (
        method === "GET"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        return success(messagePage());
      }
      if (
        method === "POST"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}/read`
      ) {
        return success(readFact());
      }
      if (
        method === "POST"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        return failure(503, "SERVICE_UNAVAILABLE", "response lost");
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, CONVERSATION_ID);
    await store.sendText(ACCESS, "原员工尚未确认的回复");
    const pendingKey = store.pendingSend?.clientMessageId;

    store.synchronizeAccess(OTHER_ACCESS);
    expect(store.operatorId).toBe(OTHER_OPERATOR_ID);
    expect(store.pendingSend).toBeNull();
    expect(store.messages).toEqual([]);

    store.synchronizeAccess(ACCESS);
    expect(store.pendingSend?.clientMessageId).toBe(pendingKey);
    expect(store.pendingSend?.content).toBe("原员工尚未确认的回复");
    expect(store.sendUnknown).toBe(true);
  });

  it("discards late history after either the operator or token changes", async () => {
    let resolveMessages!: (response: Response) => void;
    const delayedMessages = new Promise<Response>((resolve) => {
      resolveMessages = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://127.0.0.1");
      const method = requestMethod(init);
      if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
        return success([conversationFixture({
          assignedAgentId: OPERATOR_ID,
          version: 1,
        })]);
      }
      if (
        method === "GET"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}`
      ) {
        return success(conversationFixture({
          assignedAgentId: OPERATOR_ID,
          version: 1,
        }));
      }
      if (
        method === "GET"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        return delayedMessages;
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const store = useAdminChatStore();
    const request = store.refresh(ACCESS, CONVERSATION_ID);
    await vi.waitFor(() => expect(store.loadingMessages).toBe(true));
    store.synchronizeAccess(UPDATED_ACCESS);
    resolveMessages(success(messagePage()));
    await request;

    expect(store.operatorId).toBe(OPERATOR_ID);
    expect(store.messages).toEqual([]);
    expect(store.activeConversation).toBeNull();

    store.synchronizeAccess(OTHER_ACCESS);
    expect(store.operatorId).toBe(OTHER_OPERATOR_ID);
    expect(store.messages).toEqual([]);
  });

  it("accepts a lost close only after the authoritative conversation is CLOSED", async () => {
    let closed = false;
    const assigned = () => conversationFixture({
      assignedAgentId: OPERATOR_ID,
      status: closed ? "CLOSED" : "OPEN",
      version: closed ? 2 : 1,
    });
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = new URL(String(input), "http://127.0.0.1");
      const method = requestMethod(init);
      if (method === "GET" && url.pathname === "/api/v1/chat/conversations") {
        return success(closed ? [] : [assigned()]);
      }
      if (
        method === "GET"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}`
      ) {
        return success(assigned());
      }
      if (
        method === "GET"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/messages`
      ) {
        return success(messagePage());
      }
      if (
        method === "POST"
        && url.pathname === `/api/v1/chat/conversations/${CONVERSATION_ID}/read`
      ) {
        return success(readFact());
      }
      if (
        method === "POST"
        && url.pathname
          === `/api/v1/chat/conversations/${CONVERSATION_ID}/close`
      ) {
        closed = true;
        return failure(
          503,
          "SERVICE_UNAVAILABLE",
          "close response lost after commit",
        );
      }
      throw new Error(`Unexpected request: ${method} ${url.pathname}`);
    }));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, CONVERSATION_ID);
    const result = await store.closeConversation(ACCESS, CONVERSATION_ID);

    expect(result?.status).toBe("CLOSED");
    expect(store.activeConversation?.status).toBe("CLOSED");
    expect(store.operationPhase).toBe("accepted");
    expect(store.operationMessage).toContain("CLOSED");
  });

  it("does not close a conversation while a reply still has an unknown result", async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      userId: OPERATOR_ID,
      conversationId: CONVERSATION_ID,
      clientMessageId: "chat:message:unresolved-before-close",
      content: "这条回复必须先确认。",
      createdAt: "2026-08-03T00:04:00Z",
    }));
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const store = useAdminChatStore();
    store.synchronizeAccess(ACCESS);
    const result = await store.closeConversation(ACCESS, CONVERSATION_ID);

    expect(result).toBeNull();
    expect(store.operationPhase).toBe("rejected");
    expect(store.operationMessage).toContain("必须先使用原客户端消息键");
    expect(store.pendingSend?.clientMessageId)
      .toBe("chat:message:unresolved-before-close");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects malformed numeric identities without replacing the known empty queue", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      success([{
        ...conversationFixture(),
        id: 2087000000000000201,
      }])));

    const store = useAdminChatStore();
    await store.refresh(ACCESS, null);

    expect(store.conversations).toEqual([]);
    expect(store.error).toContain("字符串");
  });
});
