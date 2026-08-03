import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ApiError,
  type ApiClient,
  type ApiRequestOptions,
  type BusinessId,
} from "./api";
import {
  ChatRealtimeClient,
  createChatApi,
  createChatWebSocketUrl,
  createChatWorkspaceController,
  createChatWorkspaceState,
  parseChatRealtimeFrame,
  type ChatApi,
  type ChatConversation,
  type ChatMessage,
  type ChatMessagePage,
  type ChatWebSocketFactory,
} from "./chat";

const CUSTOMER_ID = "2079000000000000999";
const AGENT_ID = "2079000000000001999";
const CONVERSATION_ID = "2079000000000002999";
const OTHER_CONVERSATION_ID = "2079000000000003999";
const MESSAGE_ID = "2079000000000004999";
const PENDING_SEND_KEY = "test:chat:pending-send";
const PENDING_CONVERSATION_KEY = "test:chat:pending-conversation";

afterEach(() => {
  vi.useRealTimers();
});

function conversationFixture(
  overrides: Partial<ChatConversation> = {},
): ChatConversation {
  return {
    id: CONVERSATION_ID,
    conversationNo: "CHAT-20260724-0001",
    customerId: CUSTOMER_ID,
    assignedAgentId: null,
    subject: "确认商品保养方式",
    contextType: null,
    contextId: null,
    status: "OPEN",
    lastMessageSequence: 1,
    unreadCount: 0,
    version: 0,
    createdAt: "2026-07-24T01:00:00Z",
    updatedAt: "2026-07-24T01:01:00Z",
    ...overrides,
  };
}

function messageFixture(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: MESSAGE_ID,
    conversationId: CONVERSATION_ID,
    senderId: CUSTOMER_ID,
    clientMessageId: "chat:message:fixture",
    sequence: 1,
    messageType: "TEXT",
    content: "请问可以水洗吗？",
    attachments: [],
    status: "PERSISTED",
    createdAt: "2026-07-24T01:02:00Z",
    ...overrides,
  };
}

function page(items: ChatMessage[]): ChatMessagePage {
  return {
    items,
    nextBeforeSequence: null,
    hasMore: false,
  };
}

function apiFixture(overrides: Partial<ChatApi> = {}): ChatApi {
  return {
    createConversation: vi.fn(async () => conversationFixture()),
    conversations: vi.fn(async () => [conversationFixture()]),
    conversation: vi.fn(async () => conversationFixture()),
    claimConversation: vi.fn(async () =>
      conversationFixture({ assignedAgentId: AGENT_ID })),
    closeConversation: vi.fn(async () =>
      conversationFixture({ status: "CLOSED", version: 1 })),
    messages: vi.fn(async () => page([])),
    sendMessage: vi.fn(async () => messageFixture()),
    markRead: vi.fn(async (_conversationId, lastReadMessageId) => ({
      conversationId: CONVERSATION_ID,
      lastReadMessageId,
      lastReadSequence: 1,
      readAt: "2026-07-24T01:03:00Z",
    })),
    createWebSocketTicket: vi.fn(async () => ({
      ticket: "short-lived-ticket",
      targetPath: "/ws/chat",
      queryParameter: "ticket",
      expiresAt: "2026-07-24T01:04:00Z",
    })),
    ...overrides,
  };
}

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

class FakeSocket {
  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  readonly sent: string[] = [];
  readonly closes: Array<{ code?: number; reason?: string }> = [];

  send(data: string): void {
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    this.readyState = 3;
    this.closes.push({
      ...(code !== undefined ? { code } : {}),
      ...(reason !== undefined ? { reason } : {}),
    });
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }

  message(value: unknown): void {
    this.onmessage?.(new MessageEvent("message", {
      data: JSON.stringify(value),
    }));
  }

  remoteClose(): void {
    this.readyState = 3;
    this.onclose?.({} as CloseEvent);
  }
}

function workspace(
  api: ChatApi,
  storage = new MemoryStorage(),
  currentUserId: () => BusinessId | null = () => CUSTOMER_ID,
) {
  const state = createChatWorkspaceState({
    pendingSendStorageKey: PENDING_SEND_KEY,
    pendingConversationStorageKey: PENDING_CONVERSATION_KEY,
    storage,
  });
  const controller = createChatWorkspaceController({
    state,
    api,
    currentUserId,
    pendingSendStorageKey: PENDING_SEND_KEY,
    pendingConversationStorageKey: PENDING_CONVERSATION_KEY,
    storage,
    locationOrigin: "http://127.0.0.1:18200",
  });
  return { state, controller };
}

describe("Chat API and realtime protocol", () => {
  it("keeps large business IDs as strings in API paths and request bodies", async () => {
    const request = vi.fn();
    const client: ApiClient = {
      async request<T>(
        path: string,
        options?: ApiRequestOptions,
      ): Promise<T> {
        request(path, options);
        return messageFixture() as T;
      },
    };
    const api = createChatApi(client);
    const largeId = "9223372036854775806";

    await api.sendMessage(largeId, {
      clientMessageId: "chat:message:large-id",
      messageType: "TEXT",
      content: "保留字符串 ID",
      attachmentUploadIds: [],
    });

    expect(request).toHaveBeenCalledWith(
      `/api/v1/chat/conversations/${largeId}/messages`,
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          clientMessageId: "chat:message:large-id",
          messageType: "TEXT",
          content: "保留字符串 ID",
          attachmentUploadIds: [],
        }),
      }),
    );
  });

  it("builds a browser WebSocket URL from the server-issued target and query name", () => {
    expect(createChatWebSocketUrl({
      ticket: "ticket with spaces",
      targetPath: "/ws/chat",
      queryParameter: "ticket",
      expiresAt: "2026-07-24T01:04:00Z",
    }, "", "https://shop.example.com")).toBe(
      "wss://shop.example.com/ws/chat?ticket=ticket+with+spaces",
    );
  });

  it("rejects malformed message frames instead of trusting their shape", () => {
    expect(parseChatRealtimeFrame("not-json")).toBeNull();
    expect(parseChatRealtimeFrame(JSON.stringify({ type: "CHAT_MESSAGE" }))).toBeNull();
    expect(parseChatRealtimeFrame(JSON.stringify({
      type: "CHAT_MESSAGE",
      message: {
        ...messageFixture(),
        attachments: [{ id: 1 }],
      },
    }))).toBeNull();
    expect(parseChatRealtimeFrame(JSON.stringify({
      type: "CONNECTED",
      nodeId: "chat-1",
    }))).toEqual({ type: "CONNECTED", nodeId: "chat-1" });
  });

  it("uses a short ticket, accepts CHAT_MESSAGE, sends PING and closes cleanly", async () => {
    vi.useFakeTimers();
    const socket = new FakeSocket();
    const states: string[] = [];
    const messages: ChatMessage[] = [];
    const client = new ChatRealtimeClient({
      issueTicket: vi.fn(async () => ({
        ticket: "short-lived-ticket",
        targetPath: "/ws/chat",
        queryParameter: "ticket",
        expiresAt: "2026-07-24T01:04:00Z",
      })),
      onMessage: (message) => messages.push(message),
      onState: (state) => states.push(state.status),
      locationOrigin: "http://127.0.0.1:18200",
      webSocketFactory: vi.fn(() => socket) as ChatWebSocketFactory,
      heartbeatMs: 20,
    });

    client.start();
    await Promise.resolve();
    socket.open();
    socket.message({
      type: "CHAT_MESSAGE",
      nodeId: "chat-1",
      message: messageFixture(),
    });
    await vi.advanceTimersByTimeAsync(20);
    client.stop();

    expect(states).toContain("connected");
    expect(messages).toEqual([messageFixture()]);
    expect(socket.sent).toEqual([JSON.stringify({ type: "PING" })]);
    expect(socket.closes).toContainEqual({
      code: 1000,
      reason: "Workspace closed",
    });
    expect(states.at(-1)).toBe("idle");
  });

  it("stops automatic reconnect after the configured finite attempts", async () => {
    vi.useFakeTimers();
    const issueTicket = vi.fn(async () => {
      throw new Error("ticket unavailable");
    });
    const states: string[] = [];
    const messages: string[] = [];
    const client = new ChatRealtimeClient({
      issueTicket,
      onMessage: vi.fn(),
      onState: (state) => {
        states.push(state.status);
        messages.push(state.message);
      },
      reconnectDelaysMs: [5, 10],
    });

    client.start();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(5);
    await vi.advanceTimersByTimeAsync(10);

    expect(issueTicket).toHaveBeenCalledTimes(3);
    expect(states.at(-1)).toBe("unavailable");
    expect(messages.at(-1)).toContain("有限重试已结束");
    await vi.advanceTimersByTimeAsync(1000);
    expect(issueTicket).toHaveBeenCalledTimes(3);
    client.stop();
  });

  it("ignores a stale socket close after an explicit restart", async () => {
    vi.useFakeTimers();
    const sockets: FakeSocket[] = [];
    const issueTicket = vi.fn(async () => ({
      ticket: `ticket-${sockets.length}`,
      targetPath: "/ws/chat",
      queryParameter: "ticket",
      expiresAt: "2026-07-24T01:04:00Z",
    }));
    const client = new ChatRealtimeClient({
      issueTicket,
      onMessage: vi.fn(),
      onState: vi.fn(),
      webSocketFactory: vi.fn(() => {
        const socket = new FakeSocket();
        sockets.push(socket);
        return socket;
      }) as ChatWebSocketFactory,
      reconnectDelaysMs: [5],
    });

    client.start();
    await Promise.resolve();
    sockets[0]?.open();
    client.restart();
    await Promise.resolve();
    sockets[0]?.remoteClose();
    await vi.advanceTimersByTimeAsync(100);

    expect(issueTicket).toHaveBeenCalledTimes(2);
    expect(sockets).toHaveLength(2);
    client.stop();
  });
});

describe("Chat workspace consistency boundaries", () => {
  it("sorts authoritative history and retains the active conversation outside a limited queue", async () => {
    const active = conversationFixture();
    const newer = messageFixture({ id: "3", sequence: 3 });
    const older = messageFixture({ id: "1", sequence: 1 });
    const api = apiFixture({
      conversations: vi.fn(async () => [
        conversationFixture({
          id: OTHER_CONVERSATION_ID,
          conversationNo: "CHAT-20260724-0002",
          updatedAt: "2026-07-24T02:00:00Z",
        }),
      ]),
      messages: vi.fn(async () => page([newer, older])),
    });
    const { state, controller } = workspace(api);
    state.conversations = [active];
    controller.setActiveConversation(active.id);

    await controller.loadMessages();
    await controller.loadConversations();

    expect(state.messages.map((message) => message.sequence)).toEqual([1, 3]);
    expect(state.activeConversationId).toBe(active.id);
    expect(state.conversations.map((item) => item.id)).toContain(active.id);
  });

  it("recovers a lost send response by querying the original client message ID", async () => {
    let submittedClientMessageId = "";
    const persisted = messageFixture();
    const api = apiFixture({
      sendMessage: vi.fn(async (_conversationId, input) => {
        submittedClientMessageId = input.clientMessageId;
        throw new ApiError(
          "network",
          "NETWORK_UNAVAILABLE",
          "response lost",
        );
      }),
      messages: vi.fn(async () => page([{
        ...persisted,
        clientMessageId: submittedClientMessageId,
      }])),
    });
    const storage = new MemoryStorage();
    const { state, controller } = workspace(api, storage);
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    const result = await controller.sendText("请确认是否可以水洗");

    expect(result?.clientMessageId).toBe(submittedClientMessageId);
    expect(state.sendUnknown).toBe(false);
    expect(state.pendingSend).toBeNull();
    expect(storage.getItem(PENDING_SEND_KEY)).toBeNull();
  });

  it("does not let new content overwrite an unresolved message key", async () => {
    const sendMessage = vi.fn(async () => {
      throw new ApiError("timeout", "REQUEST_TIMEOUT", "timeout");
    });
    const api = apiFixture({
      sendMessage,
      messages: vi.fn(async () => page([])),
    });
    const { state, controller } = workspace(api);
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    await controller.sendText("第一条消息");
    const originalKey = state.pendingSend?.clientMessageId;
    const second = await controller.sendText("不同的新消息");

    expect(second).toBeNull();
    expect(sendMessage).toHaveBeenCalledTimes(1);
    expect(state.pendingSend?.clientMessageId).toBe(originalKey);
    expect(state.sendError).toContain("不能用新内容覆盖");
  });

  it("does not query or retry another account's pending message", async () => {
    const storage = new MemoryStorage();
    storage.setItem(PENDING_SEND_KEY, JSON.stringify({
      userId: CUSTOMER_ID,
      conversationId: CONVERSATION_ID,
      clientMessageId: "chat:message:owned-by-customer",
      content: "原账户消息",
      createdAt: "2026-07-24T01:00:00Z",
    }));
    const messages = vi.fn(async () => page([]));
    const sendMessage = vi.fn(async () => messageFixture());
    const { state, controller } = workspace(
      apiFixture({ messages, sendMessage }),
      storage,
      () => AGENT_ID,
    );
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    const result = await controller.retryPendingSend();

    expect(result).toBeNull();
    expect(messages).not.toHaveBeenCalled();
    expect(sendMessage).not.toHaveBeenCalled();
    expect(state.sendError).toContain("另一账户");
    expect(storage.getItem(PENDING_SEND_KEY)).not.toBeNull();
  });

  it("reuses the original conversation key after an unknown create result", async () => {
    const submittedKeys: string[] = [];
    const createConversation = vi.fn(async (input) => {
      submittedKeys.push(input.clientConversationId);
      if (submittedKeys.length === 1) {
        throw new ApiError("network", "NETWORK_UNAVAILABLE", "response lost");
      }
      return conversationFixture();
    });
    const { state, controller } = workspace(apiFixture({ createConversation }));

    await controller.createConversation("需要确认保养方式");
    const result = await controller.retryPendingConversation();

    expect(result?.id).toBe(CONVERSATION_ID);
    expect(submittedKeys).toHaveLength(2);
    expect(submittedKeys[1]).toBe(submittedKeys[0]);
    expect(state.pendingConversation).toBeNull();
  });

  it("does not read private messages when an unclaimed conversation claim fails", async () => {
    const messages = vi.fn(async () => page([messageFixture()]));
    const api = apiFixture({
      claimConversation: vi.fn(async () => {
        throw new ApiError("http", "CONVERSATION_ALREADY_CLAIMED", "already claimed", 409);
      }),
      messages,
    });
    const { state, controller } = workspace(api);
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    const result = await controller.claimConversation(CONVERSATION_ID);

    expect(result).toBeNull();
    expect(messages).not.toHaveBeenCalled();
    expect(state.messages).toEqual([]);
  });

  it("recovers a lost close response from the authoritative conversation fact", async () => {
    const closed = conversationFixture({ status: "CLOSED", version: 1 });
    const api = apiFixture({
      closeConversation: vi.fn(async () => {
        throw new ApiError("network", "NETWORK_UNAVAILABLE", "response lost");
      }),
      conversation: vi.fn(async () => closed),
    });
    const { state, controller } = workspace(api);
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    const result = await controller.closeConversation();
    const blockedSend = await controller.sendText("关闭后不能发送");

    expect(result).toEqual(closed);
    expect(blockedSend).toBeNull();
    expect(state.conversations[0]?.status).toBe("CLOSED");
    expect(state.sendError).toContain("已经关闭");
  });

  it("keeps unread visible until the read fact is confirmed", async () => {
    const socket = new FakeSocket();
    const markRead = vi.fn(async () => {
      throw new ApiError("network", "NETWORK_UNAVAILABLE", "read response lost");
    });
    const api = apiFixture({
      markRead,
      conversation: vi.fn(async () => conversationFixture({
        assignedAgentId: AGENT_ID,
        unreadCount: 1,
        version: 1,
      })),
    });
    const state = createChatWorkspaceState({
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
    });
    const controller = createChatWorkspaceController({
      state,
      api,
      currentUserId: () => AGENT_ID,
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
      locationOrigin: "http://127.0.0.1:18201",
      webSocketFactory: vi.fn(() => socket) as ChatWebSocketFactory,
    });
    state.conversations = [conversationFixture({
      assignedAgentId: AGENT_ID,
      unreadCount: 0,
    })];
    controller.setActiveConversation(CONVERSATION_ID);

    controller.connectRealtime();
    await Promise.resolve();
    socket.open();
    socket.message({
      type: "CHAT_MESSAGE",
      message: messageFixture({ senderId: CUSTOMER_ID, sequence: 2 }),
    });
    await vi.waitFor(() => expect(state.readError).toContain("read response lost"));

    expect(state.conversations[0]?.unreadCount).toBe(1);
    controller.disconnectRealtime();
  });

  it("reconciles agent assignment from the authoritative conversation after a realtime reply", async () => {
    const socket = new FakeSocket();
    const authoritative = conversationFixture({
      assignedAgentId: AGENT_ID,
      lastMessageSequence: 2,
      version: 2,
      updatedAt: "2026-07-24T01:04:00Z",
    });
    const conversation = vi.fn(async () => authoritative);
    const api = apiFixture({ conversation });
    const state = createChatWorkspaceState({
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
    });
    const controller = createChatWorkspaceController({
      state,
      api,
      currentUserId: () => CUSTOMER_ID,
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
      locationOrigin: "http://127.0.0.1:18200",
      webSocketFactory: vi.fn(() => socket) as ChatWebSocketFactory,
    });
    state.conversations = [conversationFixture()];
    controller.setActiveConversation(CONVERSATION_ID);

    controller.connectRealtime();
    await Promise.resolve();
    socket.open();
    socket.message({
      type: "CHAT_MESSAGE",
      message: messageFixture({
        senderId: AGENT_ID,
        sequence: 2,
      }),
    });

    await vi.waitFor(() => {
      expect(conversation).toHaveBeenCalledWith(CONVERSATION_ID);
      expect(state.conversations[0]?.assignedAgentId).toBe(AGENT_ID);
    });
    expect(state.conversations[0]?.version).toBe(2);
    controller.disconnectRealtime();
  });

  it("does not let an older conversation response regress realtime state", async () => {
    const socket = new FakeSocket();
    const conversation = vi.fn(async () => conversationFixture({
      assignedAgentId: null,
      version: 1,
    }));
    const api = apiFixture({ conversation });
    const state = createChatWorkspaceState({
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
    });
    const controller = createChatWorkspaceController({
      state,
      api,
      currentUserId: () => CUSTOMER_ID,
      pendingSendStorageKey: PENDING_SEND_KEY,
      storage: new MemoryStorage(),
      locationOrigin: "http://127.0.0.1:18200",
      webSocketFactory: vi.fn(() => socket) as ChatWebSocketFactory,
    });
    state.conversations = [conversationFixture({
      assignedAgentId: AGENT_ID,
      version: 3,
    })];
    controller.setActiveConversation(CONVERSATION_ID);

    controller.connectRealtime();
    await Promise.resolve();
    socket.open();
    socket.message({
      type: "CHAT_MESSAGE",
      message: messageFixture({
        senderId: AGENT_ID,
        sequence: 2,
      }),
    });

    await vi.waitFor(() => expect(conversation).toHaveBeenCalled());
    expect(state.conversations[0]?.assignedAgentId).toBe(AGENT_ID);
    expect(state.conversations[0]?.version).toBe(3);
    controller.disconnectRealtime();
  });
});
