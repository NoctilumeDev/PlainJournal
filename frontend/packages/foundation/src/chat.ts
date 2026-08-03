import {
  ApiError,
  createApiClient,
  type ApiClient,
  type BusinessId,
} from "./api";

export interface ChatConversation {
  id: BusinessId;
  conversationNo: string;
  customerId: BusinessId;
  assignedAgentId: BusinessId | null;
  subject: string;
  contextType: string | null;
  contextId: string | null;
  status: string;
  lastMessageSequence: number;
  unreadCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChatAttachment {
  id: BusinessId;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

export interface ChatMessage {
  id: BusinessId;
  conversationId: BusinessId;
  senderId: BusinessId;
  clientMessageId: string;
  sequence: number;
  messageType: string;
  content: string;
  attachments: ChatAttachment[];
  status: string;
  createdAt: string;
}

export interface ChatMessagePage {
  items: ChatMessage[];
  nextBeforeSequence: number | null;
  hasMore: boolean;
}

export interface ChatReadFact {
  conversationId: BusinessId;
  lastReadMessageId: BusinessId;
  lastReadSequence: number;
  readAt: string;
}

export interface ChatWebSocketTicket {
  ticket: string;
  targetPath: string;
  queryParameter: string;
  expiresAt: string;
}

export interface CreateChatConversationInput {
  clientConversationId: string;
  subject: string;
  contextType: string | null;
  contextId: string | null;
}

export interface SendChatMessageInput {
  clientMessageId: string;
  messageType: "TEXT";
  content: string;
  attachmentUploadIds: BusinessId[];
}

export interface ChatApi {
  createConversation(input: CreateChatConversationInput): Promise<ChatConversation>;
  conversations(limit?: number): Promise<ChatConversation[]>;
  conversation(conversationId: BusinessId): Promise<ChatConversation>;
  claimConversation(conversationId: BusinessId): Promise<ChatConversation>;
  closeConversation(conversationId: BusinessId): Promise<ChatConversation>;
  messages(
    conversationId: BusinessId,
    beforeSequence?: number,
    size?: number,
  ): Promise<ChatMessagePage>;
  sendMessage(
    conversationId: BusinessId,
    input: SendChatMessageInput,
  ): Promise<ChatMessage>;
  markRead(
    conversationId: BusinessId,
    lastReadMessageId: BusinessId,
  ): Promise<ChatReadFact>;
  createWebSocketTicket(): Promise<ChatWebSocketTicket>;
}

export function createChatApi(client: ApiClient): ChatApi {
  return {
    createConversation(input) {
      return client.request<ChatConversation>("/api/v1/chat/conversations", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
    conversations(limit = 50) {
      return client.request<ChatConversation[]>(
        `/api/v1/chat/conversations?limit=${encodeURIComponent(String(limit))}`,
      );
    },
    conversation(conversationId) {
      return client.request<ChatConversation>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}`,
      );
    },
    claimConversation(conversationId) {
      return client.request<ChatConversation>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/claim`,
        { method: "POST" },
      );
    },
    closeConversation(conversationId) {
      return client.request<ChatConversation>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/close`,
        { method: "POST" },
      );
    },
    messages(conversationId, beforeSequence, size = 50) {
      const query = new URLSearchParams({ size: String(size) });
      if (beforeSequence !== undefined) {
        query.set("beforeSequence", String(beforeSequence));
      }
      return client.request<ChatMessagePage>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/messages?${query}`,
      );
    },
    sendMessage(conversationId, input) {
      return client.request<ChatMessage>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/messages`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
    markRead(conversationId, lastReadMessageId) {
      return client.request<ChatReadFact>(
        `/api/v1/chat/conversations/${encodeURIComponent(conversationId)}/read`,
        {
          method: "POST",
          body: JSON.stringify({ lastReadMessageId }),
        },
      );
    },
    createWebSocketTicket() {
      return client.request<ChatWebSocketTicket>("/api/v1/chat/websocket-tickets", {
        method: "POST",
      });
    },
  };
}

export type ChatRealtimeStatus =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "unavailable";

export interface ChatRealtimeState {
  status: ChatRealtimeStatus;
  message: string;
}

export interface ChatRealtimeFrame {
  type: string;
  nodeId?: string;
  message?: ChatMessage;
}

interface ChatWebSocketLike {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent<string>) => void) | null;
  onerror: ((event: Event) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type ChatWebSocketFactory = (url: string) => ChatWebSocketLike;

export interface ChatRealtimeClientOptions {
  issueTicket: () => Promise<ChatWebSocketTicket>;
  onMessage: (message: ChatMessage) => void;
  onState: (state: ChatRealtimeState) => void;
  apiBaseUrl?: string;
  locationOrigin?: string;
  webSocketFactory?: ChatWebSocketFactory;
  heartbeatMs?: number;
  reconnectDelaysMs?: number[];
  maxReconnectAttempts?: number;
}

export function createChatWebSocketUrl(
  ticket: ChatWebSocketTicket,
  apiBaseUrl = "",
  locationOrigin = globalThis.location?.origin ?? "http://127.0.0.1",
): string {
  const origin = new URL(apiBaseUrl || locationOrigin, locationOrigin);
  origin.protocol = origin.protocol === "https:" ? "wss:" : "ws:";
  origin.pathname = ticket.targetPath;
  origin.search = "";
  origin.hash = "";
  origin.searchParams.set(ticket.queryParameter, ticket.ticket);
  return origin.toString();
}

export function parseChatRealtimeFrame(payload: string): ChatRealtimeFrame | null {
  try {
    const value: unknown = JSON.parse(payload);
    if (!value || typeof value !== "object" || !("type" in value)) {
      return null;
    }
    const frame = value as Record<string, unknown>;
    if (typeof frame.type !== "string") {
      return null;
    }
    if (frame.type !== "CHAT_MESSAGE") {
      return {
        type: frame.type,
        ...(typeof frame.nodeId === "string" ? { nodeId: frame.nodeId } : {}),
      };
    }
    if (!isChatMessage(frame.message)) {
      return null;
    }
    return {
      type: frame.type,
      ...(typeof frame.nodeId === "string" ? { nodeId: frame.nodeId } : {}),
      message: frame.message,
    };
  } catch {
    return null;
  }
}

export class ChatRealtimeClient {
  private readonly options: ChatRealtimeClientOptions;
  private socket: ChatWebSocketLike | null = null;
  private heartbeat: number | null = null;
  private reconnectTimer: number | null = null;
  private reconnectAttempt = 0;
  private generation = 0;
  private stopped = true;

  constructor(options: ChatRealtimeClientOptions) {
    this.options = options;
  }

  start() {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    this.reconnectAttempt = 0;
    const generation = ++this.generation;
    void this.open(false, generation);
  }

  stop() {
    this.stopped = true;
    this.generation += 1;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < 2) {
      socket.close(1000, "Workspace closed");
    }
    this.options.onState({
      status: "idle",
      message: "实时连接已关闭，历史消息仍可从服务端读取。",
    });
  }

  restart() {
    this.stop();
    this.start();
  }

  private async open(reconnecting: boolean, generation: number) {
    if (this.stopped || generation !== this.generation) {
      return;
    }
    this.options.onState({
      status: reconnecting ? "reconnecting" : "connecting",
      message: reconnecting
        ? "实时连接中断，正在使用新票据恢复。"
        : "正在建立实时连接，消息发送仍通过可靠接口完成。",
    });
    try {
      const ticket = await this.options.issueTicket();
      if (this.stopped || generation !== this.generation) {
        return;
      }
      const url = createChatWebSocketUrl(
        ticket,
        this.options.apiBaseUrl,
        this.options.locationOrigin,
      );
      const factory = this.options.webSocketFactory
        ?? ((target: string) => new WebSocket(target));
      const socket = factory(url);
      this.socket = socket;
      socket.onopen = () => {
        if (
          this.stopped
          || generation !== this.generation
          || socket !== this.socket
        ) {
          socket.close(1000, "Superseded");
          return;
        }
        this.reconnectAttempt = 0;
        this.options.onState({
          status: "connected",
          message: "实时连接可用；每条消息仍以 MySQL 历史为权威事实。",
        });
        this.startHeartbeat(socket);
      };
      socket.onmessage = (event) => {
        if (generation !== this.generation || socket !== this.socket) {
          return;
        }
        const frame = parseChatRealtimeFrame(event.data);
        if (frame?.type === "CHAT_MESSAGE" && frame.message) {
          this.options.onMessage(frame.message);
        }
      };
      socket.onerror = () => {
        if (generation !== this.generation || socket !== this.socket) {
          return;
        }
        this.options.onState({
          status: "unavailable",
          message: "实时连接暂不可用，页面不会把消息误标为已送达。",
        });
      };
      socket.onclose = () => {
        if (generation !== this.generation || socket !== this.socket) {
          return;
        }
        this.socket = null;
        this.clearHeartbeat();
        if (!this.stopped) {
          this.scheduleReconnect(generation);
        }
      };
    } catch {
      if (!this.stopped && generation === this.generation) {
        this.options.onState({
          status: "unavailable",
          message: "短期握手票据暂时无法取得，稍后将有限重试。",
        });
        this.scheduleReconnect(generation);
      }
    }
  }

  private startHeartbeat(socket: ChatWebSocketLike) {
    this.clearHeartbeat();
    const heartbeatMs = this.options.heartbeatMs ?? 4000;
    this.heartbeat = globalThis.setInterval(() => {
      if (socket === this.socket && socket.readyState === 1) {
        socket.send(JSON.stringify({ type: "PING" }));
      }
    }, heartbeatMs);
  }

  private scheduleReconnect(generation: number) {
    if (
      this.stopped
      || generation !== this.generation
      || this.reconnectTimer !== null
    ) {
      return;
    }
    const delays = this.options.reconnectDelaysMs ?? [1000, 2000, 5000, 10000];
    const maxAttempts = this.options.maxReconnectAttempts ?? delays.length;
    if (this.reconnectAttempt >= maxAttempts) {
      this.options.onState({
        status: "unavailable",
        message: "实时连接的有限重试已结束。历史查询仍可用，可手动重新连接。",
      });
      return;
    }
    const index = Math.min(this.reconnectAttempt, delays.length - 1);
    const delay = delays[index] ?? 10000;
    this.reconnectAttempt += 1;
    this.reconnectTimer = globalThis.setTimeout(() => {
      this.reconnectTimer = null;
      void this.open(true, generation);
    }, delay);
  }

  private clearHeartbeat() {
    if (this.heartbeat !== null) {
      globalThis.clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }

  private clearTimers() {
    this.clearHeartbeat();
    if (this.reconnectTimer !== null) {
      globalThis.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

export interface PendingChatSend {
  userId: BusinessId;
  conversationId: BusinessId;
  clientMessageId: string;
  content: string;
  createdAt: string;
}

export interface PendingChatConversation {
  userId: BusinessId;
  clientConversationId: string;
  subject: string;
  createdAt: string;
}

export interface ChatWorkspaceState {
  conversations: ChatConversation[];
  activeConversationId: BusinessId | null;
  messages: ChatMessage[];
  nextBeforeSequence: number | null;
  hasMore: boolean;
  loadingConversations: boolean;
  loadingMessages: boolean;
  loadingOlder: boolean;
  sending: boolean;
  claimingConversationId: BusinessId | null;
  closingConversationId: BusinessId | null;
  creatingConversation: boolean;
  error: string | null;
  sendError: string | null;
  sendUnknown: boolean;
  readError: string | null;
  conversationCreationError: string | null;
  conversationCreationUnknown: boolean;
  realtimeStatus: ChatRealtimeStatus;
  realtimeMessage: string;
  pendingSend: PendingChatSend | null;
  pendingConversation: PendingChatConversation | null;
}

export interface ChatWorkspaceStateOptions {
  pendingSendStorageKey: string;
  pendingConversationStorageKey?: string;
  storage?: Storage;
}

export function createChatWorkspaceState(
  options: ChatWorkspaceStateOptions,
): ChatWorkspaceState {
  const storage = options.storage ?? safeStorage();
  return {
    conversations: [],
    activeConversationId: null,
    messages: [],
    nextBeforeSequence: null,
    hasMore: false,
    loadingConversations: false,
    loadingMessages: false,
    loadingOlder: false,
    sending: false,
    claimingConversationId: null,
    closingConversationId: null,
    creatingConversation: false,
    error: null,
    sendError: null,
    sendUnknown: false,
    readError: null,
    conversationCreationError: null,
    conversationCreationUnknown: false,
    realtimeStatus: "idle",
    realtimeMessage: "实时连接尚未启动。",
    pendingSend: readStored<PendingChatSend>(
      storage,
      options.pendingSendStorageKey,
      isPendingChatSend,
    ),
    pendingConversation: options.pendingConversationStorageKey
      ? readStored<PendingChatConversation>(
          storage,
          options.pendingConversationStorageKey,
          isPendingChatConversation,
        )
      : null,
  };
}

export interface ChatWorkspaceControllerOptions {
  state: ChatWorkspaceState;
  api: ChatApi;
  currentUserId: () => BusinessId | null;
  pendingSendStorageKey: string;
  pendingConversationStorageKey?: string;
  storage?: Storage;
  apiBaseUrl?: string;
  webSocketFactory?: ChatWebSocketFactory;
  locationOrigin?: string;
}

export interface ChatWorkspaceController {
  conversation(conversationId: BusinessId): ChatConversation | null;
  activeConversation(): ChatConversation | null;
  loadConversations(): Promise<ChatConversation[]>;
  setActiveConversation(conversationId: BusinessId | null): void;
  loadActiveConversation(): Promise<ChatConversation | null>;
  loadMessages(conversationId?: BusinessId): Promise<ChatMessage[]>;
  loadOlder(): Promise<ChatMessage[]>;
  refreshActive(): Promise<void>;
  createConversation(subject: string): Promise<ChatConversation | null>;
  retryPendingConversation(): Promise<ChatConversation | null>;
  claimConversation(conversationId: BusinessId): Promise<ChatConversation | null>;
  closeConversation(conversationId?: BusinessId): Promise<ChatConversation | null>;
  sendText(content: string): Promise<ChatMessage | null>;
  retryPendingSend(): Promise<ChatMessage | null>;
  markLatestRead(): Promise<ChatReadFact | null>;
  connectRealtime(): void;
  disconnectRealtime(): void;
  restartRealtime(): void;
  clearMessages(): void;
}

export function createChatWorkspaceController(
  options: ChatWorkspaceControllerOptions,
): ChatWorkspaceController {
  const state = options.state;
  const storage = options.storage ?? safeStorage();
  let realtime: ChatRealtimeClient | null = null;

  function conversation(conversationId: BusinessId): ChatConversation | null {
    return state.conversations.find((item) => item.id === conversationId) ?? null;
  }

  function activeConversation(): ChatConversation | null {
    return state.activeConversationId
      ? conversation(state.activeConversationId)
      : null;
  }

  function upsertConversation(value: ChatConversation) {
    const index = state.conversations.findIndex((item) => item.id === value.id);
    if (index >= 0) {
      if ((state.conversations[index]?.version ?? -1) > value.version) {
        return;
      }
      state.conversations[index] = value;
    } else {
      state.conversations.push(value);
    }
    state.conversations.sort((left, right) =>
      Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
  }

  function upsertMessages(values: ChatMessage[], replace: boolean) {
    const byId = new Map<BusinessId, ChatMessage>();
    if (!replace) {
      for (const message of state.messages) {
        byId.set(message.id, message);
      }
    }
    for (const message of values) {
      byId.set(message.id, message);
    }
    state.messages = [...byId.values()].sort((left, right) =>
      left.sequence - right.sequence);
  }

  function persistSend(value: PendingChatSend | null) {
    state.pendingSend = value;
    writeStored(storage, options.pendingSendStorageKey, value);
  }

  function persistConversation(value: PendingChatConversation | null) {
    state.pendingConversation = value;
    if (options.pendingConversationStorageKey) {
      writeStored(storage, options.pendingConversationStorageKey, value);
    }
  }

  async function loadConversations(): Promise<ChatConversation[]> {
    state.loadingConversations = true;
    state.error = null;
    try {
      const current = activeConversation();
      const values = await options.api.conversations();
      const previous = new Map(
        state.conversations.map((item) => [item.id, item] as const),
      );
      state.conversations = values.map((value) => {
        const existing = previous.get(value.id);
        return existing && existing.version > value.version ? existing : value;
      });
      if (current && !values.some((item) => item.id === current.id)) {
        state.conversations.push(current);
      }
      state.conversations.sort((left, right) =>
        Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
      if (state.activeConversationId && !conversation(state.activeConversationId)) {
        state.error = "当前会话不在本次有限队列中，页面保留 URL，等待单条事实查询确认。";
      }
      return state.conversations;
    } catch (cause) {
      state.error = errorMessage(cause, "会话列表暂时无法读取。");
      return state.conversations;
    } finally {
      state.loadingConversations = false;
    }
  }

  function setActiveConversation(conversationId: BusinessId | null) {
    if (state.activeConversationId !== conversationId) {
      state.activeConversationId = conversationId;
      clearMessages();
    }
  }

  async function loadActiveConversation(): Promise<ChatConversation | null> {
    const conversationId = state.activeConversationId;
    if (!conversationId) {
      return null;
    }
    try {
      const value = await options.api.conversation(conversationId);
      upsertConversation(value);
      return value;
    } catch (cause) {
      state.error = errorMessage(cause, "会话事实暂时无法读取。");
      return null;
    }
  }

  async function loadMessages(
    conversationId = state.activeConversationId ?? "",
  ): Promise<ChatMessage[]> {
    if (!conversationId) {
      return [];
    }
    state.loadingMessages = true;
    state.error = null;
    try {
      const page = await options.api.messages(conversationId, undefined, 50);
      if (state.activeConversationId === conversationId) {
        upsertMessages(page.items, true);
        state.nextBeforeSequence = page.nextBeforeSequence;
        state.hasMore = page.hasMore;
      }
      return page.items;
    } catch (cause) {
      state.error = errorMessage(cause, "消息历史暂时无法读取。");
      return state.messages;
    } finally {
      state.loadingMessages = false;
    }
  }

  async function loadOlder(): Promise<ChatMessage[]> {
    const conversationId = state.activeConversationId;
    const before = state.nextBeforeSequence;
    if (!conversationId || before === null || state.loadingOlder) {
      return state.messages;
    }
    state.loadingOlder = true;
    state.error = null;
    try {
      const page = await options.api.messages(conversationId, before, 50);
      if (state.activeConversationId === conversationId) {
        upsertMessages(page.items, false);
        state.nextBeforeSequence = page.nextBeforeSequence;
        state.hasMore = page.hasMore;
      }
      return state.messages;
    } catch (cause) {
      state.error = errorMessage(cause, "更早的消息暂时无法读取。");
      return state.messages;
    } finally {
      state.loadingOlder = false;
    }
  }

  async function refreshActive() {
    const conversationId = state.activeConversationId;
    if (!conversationId) {
      await loadConversations();
      return;
    }
    await Promise.all([
      loadConversations(),
      loadActiveConversation(),
      loadMessages(conversationId),
    ]);
    await markLatestRead();
  }

  function prepareConversation(subject: string): PendingChatConversation | null {
    const userId = options.currentUserId();
    const normalized = subject.trim();
    if (!userId || !normalized) {
      state.conversationCreationError = "请先登录并说明需要帮助的事项。";
      return null;
    }
    const pending = state.pendingConversation;
    if (pending) {
      if (pending.userId !== userId) {
        state.conversationCreationError = "这个设备上有另一账户尚未确认的会话创建结果。";
        return null;
      }
      if (pending.subject !== normalized) {
        state.conversationCreationError = "原会话创建结果尚未确认，不能换一个主题重复创建。";
        return null;
      }
      return pending;
    }
    const value: PendingChatConversation = {
      userId,
      clientConversationId: operationKey("conversation"),
      subject: normalized,
      createdAt: new Date().toISOString(),
    };
    persistConversation(value);
    return value;
  }

  async function postPendingConversation(
    pending: PendingChatConversation,
  ): Promise<ChatConversation | null> {
    try {
      const value = await options.api.createConversation({
        clientConversationId: pending.clientConversationId,
        subject: pending.subject,
        contextType: null,
        contextId: null,
      });
      persistConversation(null);
      state.conversationCreationUnknown = false;
      state.conversationCreationError = null;
      upsertConversation(value);
      setActiveConversation(value.id);
      return value;
    } catch (cause) {
      if (isUncertain(cause)) {
        state.conversationCreationUnknown = true;
        state.conversationCreationError = "会话创建结果尚未确认，原创建键已保留，可安全重试。";
        return null;
      }
      persistConversation(null);
      state.conversationCreationUnknown = false;
      state.conversationCreationError = errorMessage(cause, "会话创建未完成。");
      return null;
    }
  }

  async function createConversation(subject: string): Promise<ChatConversation | null> {
    state.creatingConversation = true;
    state.conversationCreationError = null;
    try {
      const pending = prepareConversation(subject);
      return pending ? await postPendingConversation(pending) : null;
    } finally {
      state.creatingConversation = false;
    }
  }

  async function retryPendingConversation(): Promise<ChatConversation | null> {
    const pending = state.pendingConversation;
    if (!pending) {
      return null;
    }
    if (pending.userId !== options.currentUserId()) {
      state.conversationCreationError = "待确认会话属于另一账户，当前账户不会重试。";
      return null;
    }
    state.creatingConversation = true;
    try {
      return await postPendingConversation(pending);
    } finally {
      state.creatingConversation = false;
    }
  }

  async function claimConversation(
    conversationId: BusinessId,
  ): Promise<ChatConversation | null> {
    state.claimingConversationId = conversationId;
    state.error = null;
    try {
      const value = await options.api.claimConversation(conversationId);
      upsertConversation(value);
      setActiveConversation(value.id);
      await loadMessages(value.id);
      await markLatestRead();
      return value;
    } catch (cause) {
      state.error = errorMessage(cause, "会话认领未完成，页面没有提前开放消息内容。");
      await loadConversations();
      return null;
    } finally {
      state.claimingConversationId = null;
    }
  }

  async function closeConversation(
    requestedConversationId?: BusinessId,
  ): Promise<ChatConversation | null> {
    const conversationId = requestedConversationId ?? state.activeConversationId;
    if (!conversationId) {
      return null;
    }
    state.closingConversationId = conversationId;
    state.error = null;
    try {
      const value = await options.api.closeConversation(conversationId);
      upsertConversation(value);
      return value;
    } catch (cause) {
      try {
        const recovered = await options.api.conversation(conversationId);
        if (recovered.status === "CLOSED") {
          upsertConversation(recovered);
          return recovered;
        }
      } catch {
        // Preserve the original failure because the authoritative follow-up was also unavailable.
      }
      state.error = errorMessage(cause, "会话关闭结果尚未确认，请保留当前页面后重试。");
      return null;
    } finally {
      state.closingConversationId = null;
    }
  }

  function prepareSend(content: string): PendingChatSend | null {
    const userId = options.currentUserId();
    const conversationId = state.activeConversationId;
    const normalized = content.trim();
    if (!userId || !conversationId || !normalized) {
      state.sendError = "请选择会话并输入消息。";
      return null;
    }
    if (activeConversation()?.status !== "OPEN") {
      state.sendError = "会话已经关闭，不能继续发送消息。";
      return null;
    }
    const pending = state.pendingSend;
    if (pending) {
      if (pending.userId !== userId) {
        state.sendError = "这个设备上有另一账户尚未确认的消息。";
        return null;
      }
      if (
        pending.conversationId !== conversationId
        || pending.content !== normalized
      ) {
        state.sendError = "原消息结果尚未确认，不能用新内容覆盖原客户端消息键。";
        return null;
      }
      return pending;
    }
    const value: PendingChatSend = {
      userId,
      conversationId,
      clientMessageId: operationKey("message"),
      content: normalized,
      createdAt: new Date().toISOString(),
    };
    persistSend(value);
    return value;
  }

  async function recoverPendingSend(
    pending: PendingChatSend,
  ): Promise<ChatMessage | null> {
    try {
      const page = await options.api.messages(pending.conversationId, undefined, 100);
      const recovered = page.items.find((message) =>
        message.clientMessageId === pending.clientMessageId) ?? null;
      if (!recovered) {
        return null;
      }
      if (state.activeConversationId === pending.conversationId) {
        upsertMessages(page.items, true);
        state.nextBeforeSequence = page.nextBeforeSequence;
        state.hasMore = page.hasMore;
      }
      persistSend(null);
      state.sendUnknown = false;
      state.sendError = null;
      await loadConversations();
      return recovered;
    } catch {
      return null;
    }
  }

  async function postPendingSend(pending: PendingChatSend): Promise<ChatMessage | null> {
    try {
      const value = await options.api.sendMessage(pending.conversationId, {
        clientMessageId: pending.clientMessageId,
        messageType: "TEXT",
        content: pending.content,
        attachmentUploadIds: [],
      });
      if (state.activeConversationId === pending.conversationId) {
        upsertMessages([value], false);
      }
      persistSend(null);
      state.sendUnknown = false;
      state.sendError = null;
      await loadConversations();
      return value;
    } catch (cause) {
      if (isUncertain(cause)) {
        const recovered = await recoverPendingSend(pending);
        if (recovered) {
          return recovered;
        }
        state.sendUnknown = true;
        state.sendError = "发送结果尚未确认，原客户端消息键已保留。请先查询或安全重试。";
        return null;
      }
      persistSend(null);
      state.sendUnknown = false;
      state.sendError = errorMessage(cause, "消息未发送。");
      return null;
    }
  }

  async function sendText(content: string): Promise<ChatMessage | null> {
    state.sending = true;
    state.sendError = null;
    try {
      const pending = prepareSend(content);
      return pending ? await postPendingSend(pending) : null;
    } finally {
      state.sending = false;
    }
  }

  async function retryPendingSend(): Promise<ChatMessage | null> {
    const pending = state.pendingSend;
    if (!pending) {
      return null;
    }
    if (pending.userId !== options.currentUserId()) {
      state.sendError = "待确认消息属于另一账户，当前账户不会查询或重试。";
      return null;
    }
    state.sending = true;
    state.sendError = null;
    try {
      const recovered = await recoverPendingSend(pending);
      return recovered ?? await postPendingSend(pending);
    } finally {
      state.sending = false;
    }
  }

  async function markLatestRead(): Promise<ChatReadFact | null> {
    const conversationId = state.activeConversationId;
    const userId = options.currentUserId();
    const latest = [...state.messages]
      .reverse()
      .find((message) => message.senderId !== userId);
    if (!conversationId || !latest) {
      return null;
    }
    state.readError = null;
    try {
      const value = await options.api.markRead(conversationId, latest.id);
      const current = conversation(conversationId);
      if (current) {
        upsertConversation({ ...current, unreadCount: 0 });
      }
      return value;
    } catch (cause) {
      state.readError = errorMessage(cause, "已读位置暂时无法确认。");
      return null;
    }
  }

  async function reconcileRealtimeConversation(conversationId: BusinessId) {
    try {
      const authoritative = await options.api.conversation(conversationId);
      upsertConversation(authoritative);
    } catch (cause) {
      state.error = errorMessage(
        cause,
        "实时消息已收到，但会话状态暂时无法确认。请刷新后重试。",
      );
    }
  }

  function handleRealtimeMessage(message: ChatMessage) {
    const current = conversation(message.conversationId);
    if (current) {
      const incomingForCurrentUser = message.senderId !== options.currentUserId();
      upsertConversation({
        ...current,
        lastMessageSequence: Math.max(current.lastMessageSequence, message.sequence),
        unreadCount: incomingForCurrentUser
          ? current.unreadCount + 1
          : current.unreadCount,
        updatedAt: message.createdAt,
      });
    } else {
      void loadConversations();
    }
    void reconcileRealtimeConversation(message.conversationId);
    if (state.activeConversationId === message.conversationId) {
      upsertMessages([message], false);
      void markLatestRead();
    }
  }

  function connectRealtime() {
    if (!realtime) {
      realtime = new ChatRealtimeClient({
        issueTicket: () => options.api.createWebSocketTicket(),
        onMessage: handleRealtimeMessage,
        onState(value) {
          state.realtimeStatus = value.status;
          state.realtimeMessage = value.message;
        },
        ...(options.apiBaseUrl !== undefined ? { apiBaseUrl: options.apiBaseUrl } : {}),
        ...(options.webSocketFactory ? { webSocketFactory: options.webSocketFactory } : {}),
        ...(options.locationOrigin !== undefined
          ? { locationOrigin: options.locationOrigin }
          : {}),
      });
    }
    realtime.start();
  }

  function disconnectRealtime() {
    realtime?.stop();
  }

  function restartRealtime() {
    if (realtime) {
      realtime.restart();
    } else {
      connectRealtime();
    }
  }

  function clearMessages() {
    state.messages = [];
    state.nextBeforeSequence = null;
    state.hasMore = false;
    state.readError = null;
  }

  return {
    conversation,
    activeConversation,
    loadConversations,
    setActiveConversation,
    loadActiveConversation,
    loadMessages,
    loadOlder,
    refreshActive,
    createConversation,
    retryPendingConversation,
    claimConversation,
    closeConversation,
    sendText,
    retryPendingSend,
    markLatestRead,
    connectRealtime,
    disconnectRealtime,
    restartRealtime,
    clearMessages,
  };
}

export function createChatWorkspaceApi(
  tokenProvider: () => string | null,
  baseUrl = "",
): ChatApi {
  return createChatApi(createApiClient({
    baseUrl,
    timeoutMs: 8000,
    tokenProvider,
  }));
}

function operationKey(prefix: string): string {
  const random = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `chat:${prefix}:${random}`;
}

function safeStorage(): Storage | undefined {
  try {
    return globalThis.localStorage;
  } catch {
    return undefined;
  }
}

function readStored<T>(
  storage: Storage | undefined,
  key: string,
  guard: (value: unknown) => value is T,
): T | null {
  if (!storage) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(storage.getItem(key) ?? "null");
    return guard(value) ? value : null;
  } catch {
    return null;
  }
}

function writeStored<T>(
  storage: Storage | undefined,
  key: string,
  value: T | null,
) {
  if (!storage) {
    return;
  }
  if (value === null) {
    storage.removeItem(key);
  } else {
    storage.setItem(key, JSON.stringify(value));
  }
}

function isPendingChatSend(value: unknown): value is PendingChatSend {
  return Boolean(
    value
    && typeof value === "object"
    && "userId" in value
    && "conversationId" in value
    && "clientMessageId" in value
    && "content" in value
    && "createdAt" in value
    && typeof value.userId === "string"
    && typeof value.conversationId === "string"
    && typeof value.clientMessageId === "string"
    && typeof value.content === "string"
    && typeof value.createdAt === "string",
  );
}

function isPendingChatConversation(
  value: unknown,
): value is PendingChatConversation {
  return Boolean(
    value
    && typeof value === "object"
    && "userId" in value
    && "clientConversationId" in value
    && "subject" in value
    && "createdAt" in value
    && typeof value.userId === "string"
    && typeof value.clientConversationId === "string"
    && typeof value.subject === "string"
    && typeof value.createdAt === "string",
  );
}

function isChatAttachment(value: unknown): value is ChatAttachment {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && "fileName" in value
    && "mimeType" in value
    && "sizeBytes" in value
    && typeof value.id === "string"
    && typeof value.fileName === "string"
    && typeof value.mimeType === "string"
    && typeof value.sizeBytes === "number",
  );
}

function isChatMessage(value: unknown): value is ChatMessage {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && "conversationId" in value
    && "senderId" in value
    && "clientMessageId" in value
    && "sequence" in value
    && "messageType" in value
    && "content" in value
    && "attachments" in value
    && "status" in value
    && "createdAt" in value
    && typeof value.id === "string"
    && typeof value.conversationId === "string"
    && typeof value.senderId === "string"
    && typeof value.clientMessageId === "string"
    && typeof value.sequence === "number"
    && typeof value.messageType === "string"
    && typeof value.content === "string"
    && Array.isArray(value.attachments)
    && value.attachments.every(isChatAttachment)
    && typeof value.status === "string"
    && typeof value.createdAt === "string",
  );
}

function isUncertain(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
