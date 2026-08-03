import {
  computed,
  reactive,
  ref,
  shallowRef,
} from "vue";
import { defineStore } from "pinia";

import {
  createChatWorkspaceApi,
  createChatWorkspaceController,
  createChatWorkspaceState,
  type BusinessId,
  type ChatApi,
  type ChatAttachment,
  type ChatConversation,
  type ChatMessage,
  type ChatMessagePage,
  type ChatReadFact,
  type ChatWebSocketTicket,
  type ChatWorkspaceController,
  type ChatWorkspaceState,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_SEND_STORAGE_PREFIX =
  "plain-journal:admin-chat:pending-send:v2:";
const POLL_INTERVAL_MS = 15_000;

export interface AdminChatAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

export type AdminChatOperationKind = "claim" | "close";
export type AdminChatOperationPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

interface PendingAdminChatOperation {
  kind: AdminChatOperationKind;
  conversationId: BusinessId;
}

interface ActiveWorkspace {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  state: ChatWorkspaceState;
  controller: ChatWorkspaceController;
}

export class AdminChatContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AdminChatContractError";
  }
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 0;
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) > 0;
}

function isInstant(value: unknown): value is string {
  return typeof value === "string"
    && value.length > 0
    && Number.isFinite(Date.parse(value));
}

function validAttachment(value: unknown): value is ChatAttachment {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "fileName" in value
    && typeof value.fileName === "string"
    && value.fileName.length > 0
    && "mimeType" in value
    && typeof value.mimeType === "string"
    && value.mimeType.length > 0
    && "sizeBytes" in value
    && isPositiveInteger(value.sizeBytes),
  );
}

function validConversation(value: unknown): value is ChatConversation {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "conversationNo" in value
    && typeof value.conversationNo === "string"
    && value.conversationNo.length > 0
    && "customerId" in value
    && isBusinessId(value.customerId)
    && "assignedAgentId" in value
    && (value.assignedAgentId === null || isBusinessId(value.assignedAgentId))
    && "subject" in value
    && typeof value.subject === "string"
    && value.subject.length > 0
    && "contextType" in value
    && (value.contextType === null || typeof value.contextType === "string")
    && "contextId" in value
    && (value.contextId === null || typeof value.contextId === "string")
    && "status" in value
    && ["OPEN", "CLOSED"].includes(String(value.status))
    && "lastMessageSequence" in value
    && isNonNegativeInteger(value.lastMessageSequence)
    && "unreadCount" in value
    && isNonNegativeInteger(value.unreadCount)
    && "version" in value
    && isNonNegativeInteger(value.version)
    && "createdAt" in value
    && isInstant(value.createdAt)
    && "updatedAt" in value
    && isInstant(value.updatedAt),
  );
}

function validateConversation(
  value: ChatConversation,
  expectedId?: BusinessId,
): ChatConversation {
  if (
    !validConversation(value)
    || (expectedId !== undefined && value.id !== expectedId)
  ) {
    throw new AdminChatContractError(
      "Chat 会话事实缺少字符串身份、合法状态或与请求目标不一致。",
    );
  }
  return value;
}

function validateConversations(
  values: ChatConversation[],
): ChatConversation[] {
  if (
    !Array.isArray(values)
    || !values.every(validConversation)
    || new Set(values.map((value) => value.id)).size !== values.length
  ) {
    throw new AdminChatContractError(
      "Chat 客服队列包含非法、重复或非字符串会话身份。",
    );
  }
  return values;
}

function validMessage(value: unknown): value is ChatMessage {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "conversationId" in value
    && isBusinessId(value.conversationId)
    && "senderId" in value
    && isBusinessId(value.senderId)
    && "clientMessageId" in value
    && typeof value.clientMessageId === "string"
    && value.clientMessageId.length > 0
    && value.clientMessageId.length <= 64
    && "sequence" in value
    && isPositiveInteger(value.sequence)
    && "messageType" in value
    && ["TEXT", "IMAGE", "FILE"].includes(String(value.messageType))
    && "content" in value
    && typeof value.content === "string"
    && value.content.length <= 4000
    && "attachments" in value
    && Array.isArray(value.attachments)
    && value.attachments.every(validAttachment)
    && new Set(value.attachments.map((attachment) => attachment.id)).size
      === value.attachments.length
    && "status" in value
    && typeof value.status === "string"
    && value.status.length > 0
    && "createdAt" in value
    && isInstant(value.createdAt),
  );
}

function validateMessage(
  value: ChatMessage,
  expectedConversationId: BusinessId,
  expectedClientMessageId?: string,
  expectedSenderId?: BusinessId,
): ChatMessage {
  if (
    !validMessage(value)
    || value.conversationId !== expectedConversationId
    || (
      expectedClientMessageId !== undefined
      && value.clientMessageId !== expectedClientMessageId
    )
    || (
      expectedSenderId !== undefined
      && value.senderId !== expectedSenderId
    )
  ) {
    throw new AdminChatContractError(
      "Chat 消息响应与会话、发送者或原客户端消息键不一致。",
    );
  }
  return value;
}

function validateMessagePage(
  value: ChatMessagePage,
  conversationId: BusinessId,
  beforeSequence: number | undefined,
  size: number,
): ChatMessagePage {
  if (
    !value
    || typeof value !== "object"
    || !Array.isArray(value.items)
    || value.items.length > size
    || !value.items.every((message) =>
      validMessage(message)
      && message.conversationId === conversationId
      && (
        beforeSequence === undefined
        || message.sequence < beforeSequence
      ))
    || new Set(value.items.map((message) => message.id)).size
      !== value.items.length
    || new Set(value.items.map((message) => message.sequence)).size
      !== value.items.length
    || !value.items.every((message, index, items) =>
      index === 0 || (items[index - 1]?.sequence ?? 0) < message.sequence)
    || !(
      value.nextBeforeSequence === null
      || isPositiveInteger(value.nextBeforeSequence)
    )
    || typeof value.hasMore !== "boolean"
    || (value.hasMore && value.nextBeforeSequence === null)
  ) {
    throw new AdminChatContractError(
      "Chat 历史分页与会话身份、顺序或游标契约不一致。",
    );
  }
  return value;
}

function validateReadFact(
  value: ChatReadFact,
  conversationId: BusinessId,
  lastReadMessageId: BusinessId,
): ChatReadFact {
  if (
    !value
    || typeof value !== "object"
    || !isBusinessId(value.conversationId)
    || value.conversationId !== conversationId
    || !isBusinessId(value.lastReadMessageId)
    || value.lastReadMessageId !== lastReadMessageId
    || !isPositiveInteger(value.lastReadSequence)
    || !isInstant(value.readAt)
  ) {
    throw new AdminChatContractError(
      "Chat 已读响应与会话或最后消息身份不一致。",
    );
  }
  return value;
}

function validateTicket(value: ChatWebSocketTicket): ChatWebSocketTicket {
  if (
    !value
    || typeof value !== "object"
    || typeof value.ticket !== "string"
    || value.ticket.length === 0
    || value.targetPath !== "/ws/chat"
    || value.queryParameter !== "ticket"
    || !isInstant(value.expiresAt)
  ) {
    throw new AdminChatContractError(
      "Chat WebSocket 票据没有遵守固定路径、查询参数或时效契约。",
    );
  }
  return value;
}

function guardedApi(
  accessToken: string,
  operatorId: BusinessId,
): ChatApi {
  const raw = createChatWorkspaceApi(() => accessToken, apiBaseUrl);
  return {
    async createConversation(input) {
      return validateConversation(await raw.createConversation(input));
    },
    async conversations(limit) {
      return validateConversations(await raw.conversations(limit));
    },
    async conversation(conversationId) {
      return validateConversation(
        await raw.conversation(conversationId),
        conversationId,
      );
    },
    async claimConversation(conversationId) {
      const value = validateConversation(
        await raw.claimConversation(conversationId),
        conversationId,
      );
      if (value.assignedAgentId !== operatorId) {
        throw new AdminChatContractError(
          "Chat 认领接口返回了其他客服的成员事实。",
        );
      }
      return value;
    },
    async closeConversation(conversationId) {
      const value = validateConversation(
        await raw.closeConversation(conversationId),
        conversationId,
      );
      if (value.status !== "CLOSED") {
        throw new AdminChatContractError(
          "Chat 关闭接口没有返回 CLOSED 权威状态。",
        );
      }
      return value;
    },
    async messages(conversationId, beforeSequence, size = 50) {
      return validateMessagePage(
        await raw.messages(conversationId, beforeSequence, size),
        conversationId,
        beforeSequence,
        size,
      );
    },
    async sendMessage(conversationId, input) {
      return validateMessage(
        await raw.sendMessage(conversationId, input),
        conversationId,
        input.clientMessageId,
        operatorId,
      );
    },
    async markRead(conversationId, lastReadMessageId) {
      return validateReadFact(
        await raw.markRead(conversationId, lastReadMessageId),
        conversationId,
        lastReadMessageId,
      );
    },
    async createWebSocketTicket() {
      return validateTicket(await raw.createWebSocketTicket());
    },
  };
}

function isActiveContext(
  context: AdminChatAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && isBusinessId(context.operatorId)
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function pendingSendStorageKey(operatorId: BusinessId): string {
  return `${PENDING_SEND_STORAGE_PREFIX}${operatorId}`;
}

export const useAdminChatStore = defineStore("admin-chat", () => {
  const workspace = shallowRef<ActiveWorkspace | null>(null);
  const routeConversationId = ref<BusinessId | null>(null);
  const operationPhase = ref<AdminChatOperationPhase>("idle");
  const operationMessage = ref<string | null>(null);
  const pendingOperation = ref<PendingAdminChatOperation | null>(null);
  let workspaceRevision = 0;
  let running = false;
  let pollTimer: number | null = null;

  const state = computed(() => workspace.value?.state ?? null);
  const operatorId = computed(() => workspace.value?.operatorId ?? null);
  const conversations = computed(() => state.value?.conversations ?? []);
  const activeConversationId = computed(() =>
    state.value?.activeConversationId ?? null);
  const activeConversation = computed(() => {
    const current = workspace.value;
    return current?.controller.activeConversation() ?? null;
  });
  const messages = computed(() => state.value?.messages ?? []);
  const nextBeforeSequence = computed(() =>
    state.value?.nextBeforeSequence ?? null);
  const hasMore = computed(() => state.value?.hasMore ?? false);
  const loadingConversations = computed(() =>
    state.value?.loadingConversations ?? false);
  const loadingMessages = computed(() =>
    state.value?.loadingMessages ?? false);
  const loadingOlder = computed(() => state.value?.loadingOlder ?? false);
  const sending = computed(() => state.value?.sending ?? false);
  const claimingConversationId = computed(() =>
    state.value?.claimingConversationId ?? null);
  const closingConversationId = computed(() =>
    state.value?.closingConversationId ?? null);
  const error = computed(() => state.value?.error ?? null);
  const sendError = computed(() => state.value?.sendError ?? null);
  const sendUnknown = computed(() => state.value?.sendUnknown ?? false);
  const readError = computed(() => state.value?.readError ?? null);
  const realtimeStatus = computed(() =>
    state.value?.realtimeStatus ?? "idle");
  const realtimeMessage = computed(() =>
    state.value?.realtimeMessage ?? "实时连接尚未启动。");
  const pendingSend = computed(() => state.value?.pendingSend ?? null);
  const assignedToMe = computed(() =>
    Boolean(
      activeConversation.value
      && activeConversation.value.assignedAgentId === operatorId.value,
    ));
  const assignedToOther = computed(() =>
    Boolean(
      activeConversation.value?.assignedAgentId
      && activeConversation.value.assignedAgentId !== operatorId.value,
    ));
  const operationBlocked = computed(() =>
    operationPhase.value === "processing"
    || operationPhase.value === "unknown");
  const canRetryOperation = computed(() =>
    operationPhase.value === "unknown"
    && pendingOperation.value !== null);

  function workspaceIsCurrent(candidate: ActiveWorkspace): boolean {
    return workspace.value === candidate
      && candidate.revision === workspaceRevision;
  }

  function synchronizeAccess(
    context: AdminChatAccessContext,
  ): ActiveWorkspace | null {
    const current = workspace.value;
    const nextOperatorId = isActiveContext(context)
      ? context.operatorId
      : null;
    const nextAccessToken = isActiveContext(context)
      ? context.accessToken
      : null;
    if (
      current
      && current.operatorId === nextOperatorId
      && current.accessToken === nextAccessToken
    ) {
      return current;
    }

    current?.controller.disconnectRealtime();
    workspaceRevision += 1;
    const operatorChanged = current?.operatorId !== nextOperatorId;
    if (operatorChanged) {
      pendingOperation.value = null;
      operationPhase.value = "idle";
      operationMessage.value = null;
    } else if (pendingOperation.value) {
      operationPhase.value = "unknown";
      operationMessage.value =
        "员工会话凭据已更新，原客服操作继续保持结果未知；只能针对同一会话重试。";
    }

    if (!isActiveContext(context)) {
      workspace.value = null;
      return null;
    }

    const chatState = reactive(createChatWorkspaceState({
      pendingSendStorageKey: pendingSendStorageKey(context.operatorId),
    }));
    if (chatState.pendingSend) {
      chatState.sendUnknown = true;
      chatState.sendError = chatState.pendingSend.userId === context.operatorId
        ? "发现一条尚未确认的客服回复。原客户端消息键与正文已恢复，只能查询或原样重试。"
        : "待确认客服回复的所有者与当前员工不一致，当前工作区不会查询或重试。";
    }
    const controller = createChatWorkspaceController({
      state: chatState,
      api: guardedApi(context.accessToken, context.operatorId),
      currentUserId: () => context.operatorId,
      pendingSendStorageKey: pendingSendStorageKey(context.operatorId),
      apiBaseUrl,
    });
    const next: ActiveWorkspace = {
      operatorId: context.operatorId,
      accessToken: context.accessToken,
      revision: workspaceRevision,
      state: chatState,
      controller,
    };
    workspace.value = next;
    if (running) {
      controller.connectRealtime();
    }
    return next;
  }

  async function markLatestRead(
    candidate = workspace.value,
  ): Promise<ChatReadFact | null> {
    if (!candidate) {
      return null;
    }
    const result = await candidate.controller.markLatestRead();
    return workspaceIsCurrent(candidate) ? result : null;
  }

  async function refreshCurrent(): Promise<void> {
    const current = workspace.value;
    if (!current) {
      return;
    }
    await current.controller.loadConversations();
    if (!workspaceIsCurrent(current)) {
      return;
    }

    const conversationId = routeConversationId.value;
    current.controller.setActiveConversation(conversationId);
    if (!conversationId) {
      return;
    }

    let selected = current.controller.conversation(conversationId);
    if (!selected) {
      selected = await current.controller.loadActiveConversation();
      if (!workspaceIsCurrent(current)) {
        return;
      }
    }
    if (selected?.assignedAgentId !== current.operatorId) {
      current.controller.clearMessages();
      return;
    }

    const [, page] = await Promise.all([
      current.controller.loadActiveConversation(),
      current.controller.loadMessages(conversationId),
    ]);
    if (!workspaceIsCurrent(current)) {
      return;
    }
    if (page.length > 0) {
      await markLatestRead(current);
    }
  }

  async function refresh(
    context: AdminChatAccessContext,
    conversationId: BusinessId | null = routeConversationId.value,
  ): Promise<void> {
    synchronizeAccess(context);
    routeConversationId.value = conversationId;
    await refreshCurrent();
  }

  async function start(
    context: AdminChatAccessContext,
    conversationId: BusinessId | null,
  ): Promise<void> {
    running = true;
    synchronizeAccess(context);
    routeConversationId.value = conversationId;
    workspace.value?.controller.connectRealtime();
    await refreshCurrent();
    if (pollTimer !== null) {
      globalThis.clearInterval(pollTimer);
    }
    pollTimer = globalThis.setInterval(() => {
      void refreshCurrent();
    }, POLL_INTERVAL_MS);
  }

  function stop() {
    running = false;
    workspace.value?.controller.disconnectRealtime();
    if (pollTimer !== null) {
      globalThis.clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  async function updateAccess(context: AdminChatAccessContext): Promise<void> {
    synchronizeAccess(context);
    if (running) {
      workspace.value?.controller.connectRealtime();
      await refreshCurrent();
    }
  }

  async function updateRoute(
    context: AdminChatAccessContext,
    conversationId: BusinessId | null,
  ): Promise<void> {
    synchronizeAccess(context);
    routeConversationId.value = conversationId;
    if (running) {
      await refreshCurrent();
    }
  }

  function beginOperation(
    kind: AdminChatOperationKind,
    conversationId: BusinessId,
  ) {
    pendingOperation.value = { kind, conversationId };
    operationPhase.value = "processing";
    operationMessage.value = kind === "claim"
      ? "正在等待 Chat 确认客服成员事实。"
      : "正在等待 Chat 确认会话关闭事实。";
  }

  function acceptOperation(message: string) {
    pendingOperation.value = null;
    operationPhase.value = "accepted";
    operationMessage.value = message;
  }

  function rejectOperation(message: string) {
    pendingOperation.value = null;
    operationPhase.value = "rejected";
    operationMessage.value = message;
  }

  function keepOperationUnknown(message: string) {
    operationPhase.value = "unknown";
    operationMessage.value = message;
  }

  async function claimConversation(
    context: AdminChatAccessContext,
    conversationId: BusinessId,
  ): Promise<ChatConversation | null> {
    const current = synchronizeAccess(context);
    if (!current) {
      rejectOperation("当前会话无权认领客服会话。");
      return null;
    }
    if (
      pendingOperation.value
      && (
        pendingOperation.value.kind !== "claim"
        || pendingOperation.value.conversationId !== conversationId
      )
    ) {
      keepOperationUnknown(
        "上一项客服操作尚未确认，不能对另一会话发起新的认领或关闭。",
      );
      return null;
    }

    current.controller.setActiveConversation(conversationId);
    beginOperation("claim", conversationId);
    const result = await current.controller.claimConversation(conversationId);
    if (!workspaceIsCurrent(current)) {
      return null;
    }
    if (result?.assignedAgentId === current.operatorId) {
      acceptOperation("Chat 已确认当前员工为会话成员，私聊正文现已开放。");
      return result;
    }

    const queueFact = current.controller.conversation(conversationId);
    if (queueFact?.assignedAgentId === current.operatorId) {
      const authority = await current.controller.loadActiveConversation();
      if (!workspaceIsCurrent(current)) {
        return null;
      }
      if (authority?.assignedAgentId === current.operatorId) {
        await current.controller.loadMessages(conversationId);
        await markLatestRead(current);
        acceptOperation(
          "认领响应未返回，但 Chat 权威成员事实已确认当前员工；页面没有提前读取正文。",
        );
        return authority;
      }
    }
    if (
      queueFact?.assignedAgentId
      && queueFact.assignedAgentId !== current.operatorId
    ) {
      rejectOperation(
        `Chat 已确认会话由客服 ${queueFact.assignedAgentId} 处理，当前员工不能读取正文。`,
      );
      return null;
    }

    keepOperationUnknown(
      "认领结果尚未确认。页面仍然隐藏私聊正文，只允许针对同一会话重新认领。",
    );
    return null;
  }

  async function closeConversation(
    context: AdminChatAccessContext,
    conversationId = activeConversationId.value,
  ): Promise<ChatConversation | null> {
    const current = synchronizeAccess(context);
    if (!current || !conversationId) {
      rejectOperation("当前没有可关闭的客服会话。");
      return null;
    }
    if (current.state.pendingSend) {
      rejectOperation(
        "仍有一条客服回复结果未知。必须先使用原客户端消息键确认回复，再关闭会话。",
      );
      return null;
    }
    if (
      pendingOperation.value
      && (
        pendingOperation.value.kind !== "close"
        || pendingOperation.value.conversationId !== conversationId
      )
    ) {
      keepOperationUnknown(
        "上一项客服操作尚未确认，不能对另一会话发起新的认领或关闭。",
      );
      return null;
    }

    beginOperation("close", conversationId);
    const result = await current.controller.closeConversation(conversationId);
    if (!workspaceIsCurrent(current)) {
      return null;
    }
    if (result?.status === "CLOSED") {
      acceptOperation(
        "Chat 权威会话事实已确认 CLOSED，历史消息继续只读保留。",
      );
      return result;
    }
    keepOperationUnknown(
      "关闭结果尚未确认。页面没有显示成功，只允许对同一会话重试关闭。",
    );
    return null;
  }

  function retryPendingOperation(
    context: AdminChatAccessContext,
  ): Promise<ChatConversation | null> {
    const pending = pendingOperation.value;
    if (!pending) {
      rejectOperation("当前没有可重试的客服操作。");
      return Promise.resolve(null);
    }
    return pending.kind === "claim"
      ? claimConversation(context, pending.conversationId)
      : closeConversation(context, pending.conversationId);
  }

  async function sendText(
    context: AdminChatAccessContext,
    content: string,
  ): Promise<ChatMessage | null> {
    const current = synchronizeAccess(context);
    if (!current) {
      return null;
    }
    if (pendingOperation.value) {
      current.state.sendError =
        "客服认领或关闭结果尚未确认，当前工作区不会发送新回复。";
      return null;
    }
    const result = await current.controller.sendText(content);
    return workspaceIsCurrent(current) ? result : null;
  }

  async function retryPendingSend(
    context: AdminChatAccessContext,
  ): Promise<ChatMessage | null> {
    const current = synchronizeAccess(context);
    if (!current) {
      return null;
    }
    const result = await current.controller.retryPendingSend();
    return workspaceIsCurrent(current) ? result : null;
  }

  async function loadOlder(
    context: AdminChatAccessContext,
  ): Promise<ChatMessage[]> {
    const current = synchronizeAccess(context);
    if (!current) {
      return [];
    }
    const result = await current.controller.loadOlder();
    return workspaceIsCurrent(current) ? result : [];
  }

  async function retryRead(
    context: AdminChatAccessContext,
  ): Promise<ChatReadFact | null> {
    const current = synchronizeAccess(context);
    return current ? markLatestRead(current) : null;
  }

  function restartRealtime(context: AdminChatAccessContext) {
    const current = synchronizeAccess(context);
    current?.controller.restartRealtime();
  }

  function resetOperationNotice() {
    if (pendingOperation.value) {
      return;
    }
    operationPhase.value = "idle";
    operationMessage.value = null;
  }

  return {
    operatorId,
    conversations,
    activeConversationId,
    activeConversation,
    messages,
    nextBeforeSequence,
    hasMore,
    loadingConversations,
    loadingMessages,
    loadingOlder,
    sending,
    claimingConversationId,
    closingConversationId,
    error,
    sendError,
    sendUnknown,
    readError,
    realtimeStatus,
    realtimeMessage,
    pendingSend,
    assignedToMe,
    assignedToOther,
    operationPhase,
    operationMessage,
    pendingOperation,
    operationBlocked,
    canRetryOperation,
    synchronizeAccess,
    refresh,
    start,
    stop,
    updateAccess,
    updateRoute,
    claimConversation,
    closeConversation,
    retryPendingOperation,
    sendText,
    retryPendingSend,
    loadOlder,
    retryRead,
    restartRealtime,
    resetOperationNotice,
  };
});
